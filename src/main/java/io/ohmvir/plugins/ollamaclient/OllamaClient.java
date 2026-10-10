package io.ohmvir.plugins.ollamaclient;

import static java.util.Map.entry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import hudson.Extension;
import hudson.model.Descriptor;
import io.ohmvir.plugins.jenkinsaisynapse.api.ModelContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.client.ModelClient;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelFinishReason;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.skills.SkillData;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Extension
public class OllamaClient extends ModelClient<OllamaModelSettings, OllamaClientSettings> {
    private static final Logger LOGGER = Logger.getLogger(OllamaClient.class.getName());
    private static final String CHAT_API_SUFFIX = "/api/chat";

    private static final Map<Class<?>, String> TYPE_TO_OLLAMA_TYPE_NAME = Map.ofEntries(
            // String & Identifier Types
            entry(String.class, "string"),
            entry(UUID.class, "string"),
            entry(char.class, "string"),
            entry(Character.class, "string"),

            // Primitive & Object Integers
            entry(int.class, "integer"),
            entry(Integer.class, "integer"),
            entry(long.class, "integer"),
            entry(Long.class, "integer"),
            entry(short.class, "integer"),
            entry(Short.class, "integer"),
            entry(byte.class, "integer"),
            entry(Byte.class, "integer"),

            // Floating Point / Decimal Numbers
            entry(float.class, "number"),
            entry(Float.class, "number"),
            entry(double.class, "number"),
            entry(Double.class, "number"),
            entry(BigDecimal.class, "number"),
            entry(BigInteger.class, "integer"),

            // Booleans
            entry(boolean.class, "boolean"),
            entry(Boolean.class, "boolean"),

            // Collections & Arrays
            entry(List.class, "array"),
            entry(Collection.class, "array"),

            // Default Object / Map Target
            entry(Object.class, "object"),
            entry(Map.class, "object"));

    // "load" and "unload" are only returned for requests without messages, they map to the default of STOP
    private static final Map<String, ModelFinishReason> OLLAMA_FINISH_REASON_TO_MODEL_API =
            Map.of("stop", ModelFinishReason.STOP, "length", ModelFinishReason.TOKEN_CAP);

    /**
     * An assistant message produced by an earlier step of the same request.
     * @param inputIndex The number of turn inputs that existed when the message was produced, used to slot it back
     *                   in front of the tool call responses that answer it.
     */
    record AssistantTurn(int inputIndex, JsonObject message) {}

    /**
     * {@link ModelRequest#execute()} calls {@link #takeStep} in a loop until no outputs are returned, and only passes
     * back the inputs, so the assistant side of the exchange has to be tracked here.
     */
    static class RequestState {
        int lastProcessedInputCount = -1;
        boolean lastStepHadToolCalls = false;
        final List<AssistantTurn> assistantTurns = new ArrayList<>();
        final Map<String, String> toolNamesByCallId = new HashMap<>();
    }

    private final Map<ModelRequest, RequestState> requestStates = Collections.synchronizedMap(new WeakHashMap<>());

    @Override
    public List<ModelOutput> takeStepImpl(
            ModelData modelData,
            OllamaModelSettings configuration,
            OllamaClientSettings clientConfiguration,
            ModelRequest request,
            List<ModelInput> turnInputs) {

        RequestState state = requestStates.computeIfAbsent(request, r -> new RequestState());

        // The model already answered and no tool call responses were added since, end the request loop
        if (state.lastProcessedInputCount == turnInputs.size() && !state.lastStepHadToolCalls) {
            requestStates.remove(request);
            return List.of();
        }
        state.lastProcessedInputCount = turnInputs.size();
        state.lastStepHadToolCalls = false;

        if (clientConfiguration == null) {
            clientConfiguration = OllamaClientSettings.get();
        }
        String baseUrl = clientConfiguration.resolveApiBaseUrl(null);
        if (baseUrl == null) {
            LOGGER.severe("Cannot call Ollama: API base URL credential is not configured or could not be resolved");
            requestStates.remove(request);
            return List.of();
        }

        JsonObject apiReq = buildChatRequest(
                configuration.getModelName(), clientConfiguration.getKeepAliveSeconds(), turnInputs, state);

        long timeoutSeconds =
                clientConfiguration.getTimeoutSeconds() == null ? 0L : clientConfiguration.getTimeoutSeconds();
        HttpClient.Builder clientBuilder = HttpClient.newBuilder();
        HttpRequest.Builder httpBuilder = HttpRequest.newBuilder(
                        OllamaClientSettings.buildApiUri(baseUrl, CHAT_API_SUFFIX))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(apiReq.toString()));
        // 0 means no timeout, Duration.ZERO is rejected by the HttpClient builders
        if (timeoutSeconds > 0) {
            clientBuilder.connectTimeout(Duration.ofSeconds(timeoutSeconds));
            httpBuilder.timeout(Duration.ofSeconds(timeoutSeconds));
        }
        clientConfiguration.applyCloudflareAccessHeaders(httpBuilder, null);

        try (HttpClient client = clientBuilder.build()) {
            HttpResponse<String> response = client.send(httpBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.log(Level.SEVERE, "Ollama API returned HTTP {0}: {1}", new Object[] {
                    response.statusCode(), response.body()
                });
                requestStates.remove(request);
                return List.of();
            }

            JsonObject resJson = JsonParser.parseString(response.body()).getAsJsonObject();
            return parseChatResponse(resJson, request.getOutputClasses(), state, turnInputs.size());
        } catch (IOException | JsonParseException | IllegalStateException e) {
            LOGGER.log(Level.SEVERE, "Ollama chat request failed: " + e.getMessage(), e);
            requestStates.remove(request);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.SEVERE, "Interrupted while waiting for Ollama", e);
            requestStates.remove(request);
            return List.of();
        }
    }

    static JsonObject buildChatRequest(
            String modelName, long keepAliveSeconds, List<ModelInput> turnInputs, RequestState state) {
        JsonObject apiReq = new JsonObject();
        JsonObject options = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonArray tools = new JsonArray();
        JsonArray pendingImages = new JsonArray();
        List<String> systemPromptParts = new ArrayList<>();
        JsonObject lastUserMessage = null;

        apiReq.addProperty("model", modelName);
        apiReq.addProperty("stream", false);

        if (keepAliveSeconds > 0) {
            apiReq.addProperty("keep_alive", keepAliveSeconds + "s");
        }

        for (int i = 0; i < turnInputs.size(); i++) {
            addAssistantTurns(state, i, messages);
            switch (turnInputs.get(i)) {
                case InputImageContent imageContent -> {
                    addImage(pendingImages, imageContent.getImageBase64());
                }

                case InputAudioContent audioContent -> {
                    if (audioContent.getAudioData() != null) {
                        pendingImages.add(Base64.getEncoder().encodeToString(audioContent.getAudioData()));
                    }
                }

                case InputConversationContent conversationContent -> {
                    addConversationMessages(conversationContent, messages);
                }

                case InputTextContent textContent -> {
                    lastUserMessage = createMessage("user", textContent.getText(), pendingImages);
                    messages.add(lastUserMessage);
                }

                case SystemPromptContent systemPromptContent -> {
                    systemPromptParts.add(systemPromptContent.getSystemPrompt());
                }

                case InputSkillContent skillContent -> {
                    systemPromptParts.add(skillToSystemPrompt(skillContent.getSkill()));
                }

                case InputToolContent toolContent -> {
                    tools.add(toolContentToOllamaJson(toolContent));
                }

                case ToolCallResponseContent toolCallResponse -> {
                    messages.add(toolCallResponseToOllamaJson(toolCallResponse, state.toolNamesByCallId));
                }

                case MaxOutputTokensContent maxOutputTokensContent -> {
                    options.addProperty("num_predict", maxOutputTokensContent.getMaxOutputTokens());
                }

                case StopSequencesContent stopSequencesContent -> {
                    JsonArray sequencesJsonArr = new JsonArray();
                    stopSequencesContent.getStopPhrases().forEach(sequencesJsonArr::add);
                    options.add("stop", sequencesJsonArr);
                }

                case TemperatureContent temperatureContent -> {
                    options.addProperty("temperature", temperatureContent.getTemperature());
                }

                case TopKContent topKContent -> {
                    options.addProperty("top_k", Math.round(topKContent.getTopK()));
                }

                case TopPContent topPContent -> {
                    options.addProperty("top_p", topPContent.getTopP());
                }

                case ThinkingLevelContent thinkingLevelContent -> {
                    apiReq.add("think", thinkingLevelToOllamaJson(thinkingLevelContent.getThinkingLevel(), modelName));
                }

                default -> {}
            }
        }
        addAssistantTurns(state, turnInputs.size(), messages);

        // Ollama only accepts images as part of a message, so attach images that came after the last text input
        if (!pendingImages.isEmpty()) {
            if (lastUserMessage != null) {
                appendImages(lastUserMessage, pendingImages);
            } else {
                messages.add(createMessage("user", "", pendingImages));
            }
        }

        // Many chat templates only honor a leading system message
        if (!systemPromptParts.isEmpty()) {
            JsonArray messagesWithSystemPrompt = new JsonArray();
            messagesWithSystemPrompt.add(createMessage("system", String.join("\n\n", systemPromptParts), null));
            messagesWithSystemPrompt.addAll(messages);
            messages = messagesWithSystemPrompt;
        }

        apiReq.add("messages", messages);
        if (!options.isEmpty()) {
            apiReq.add("options", options);
        }
        if (!tools.isEmpty()) {
            apiReq.add("tools", tools);
        }
        return apiReq;
    }

    static List<ModelOutput> parseChatResponse(
            JsonObject resJson, Set<Class<? extends ModelOutput>> outputClasses, RequestState state, int inputCount) {
        JsonObject messageObj = resJson.has("message") && resJson.get("message").isJsonObject()
                ? resJson.getAsJsonObject("message")
                : new JsonObject();
        String content = getString(messageObj, "content");
        String thinking = getString(messageObj, "thinking");

        List<ToolCallContent> toolCalls = new ArrayList<>();
        JsonArray toolCallsToReplay = new JsonArray();
        if (messageObj.has("tool_calls") && messageObj.get("tool_calls").isJsonArray()) {
            for (JsonElement toolCallElement : messageObj.getAsJsonArray("tool_calls")) {
                if (!toolCallElement.isJsonObject()) {
                    continue;
                }
                JsonObject toolCallObj = toolCallElement.getAsJsonObject().deepCopy();
                JsonObject fnObj = toolCallObj.has("function")
                                && toolCallObj.get("function").isJsonObject()
                        ? toolCallObj.getAsJsonObject("function")
                        : null;
                String toolName = fnObj == null ? null : getString(fnObj, "name");
                if (toolName == null || toolName.isBlank()) {
                    LOGGER.log(Level.WARNING, "Ignoring Ollama tool call without a function name: {0}", toolCallObj);
                    continue;
                }
                // Newer Ollama versions return an id, generate one otherwise so responses can be matched up
                String toolUseId = getString(toolCallObj, "id");
                if (toolUseId == null || toolUseId.isBlank()) {
                    toolUseId = UUID.randomUUID().toString();
                    toolCallObj.addProperty("id", toolUseId);
                }
                toolCalls.add(new ToolCallContent(toolUseId, toolArgumentsToJson(fnObj.get("arguments")), toolName));
                state.toolNamesByCallId.put(toolUseId, toolName);
                toolCallsToReplay.add(toolCallObj);
            }
        }

        JsonObject assistantMessage = createMessage("assistant", content == null ? "" : content, null);
        if (thinking != null && !thinking.isEmpty()) {
            assistantMessage.addProperty("thinking", thinking);
        }
        if (!toolCallsToReplay.isEmpty()) {
            assistantMessage.add("tool_calls", toolCallsToReplay);
        }
        state.assistantTurns.add(new AssistantTurn(inputCount, assistantMessage));
        state.lastStepHadToolCalls = !toolCalls.isEmpty();

        List<ModelOutput> outputs = new ArrayList<>();
        if (outputClasses.contains(ThinkingContent.class) && thinking != null && !thinking.isEmpty()) {
            outputs.add(new ThinkingContent(thinking));
        }
        if (outputClasses.contains(OutputTextContent.class) && content != null && !content.isEmpty()) {
            outputs.add(new OutputTextContent(content));
        }
        // Tool calls are always returned so ModelRequest#execute can run them and send back the results
        outputs.addAll(toolCalls);
        if (outputClasses.contains(FinishReasonContent.class)) {
            String doneReason = getString(resJson, "done_reason");
            if (!toolCalls.isEmpty()) {
                // Ollama reports "stop" when the model stops to wait for tool calls
                outputs.add(new FinishReasonContent(ModelFinishReason.TOOL_CALLS));
            } else if (doneReason != null) {
                outputs.add(new FinishReasonContent(
                        OLLAMA_FINISH_REASON_TO_MODEL_API.getOrDefault(doneReason, ModelFinishReason.STOP)));
            }
        }
        if (outputClasses.contains(TokenUtilizationContent.class)) {
            outputs.add(new TokenUtilizationContent(
                    getLong(resJson, "prompt_eval_count"), getLong(resJson, "eval_count"), 0));
        }
        return outputs;
    }

    static JsonPrimitive thinkingLevelToOllamaJson(ModelThinkingLevel thinkingLevel, String modelName) {
        return switch (thinkingLevel) {
            case OFF -> new JsonPrimitive(false);
            case ON -> new JsonPrimitive(true);
            case LOW -> new JsonPrimitive("low");
            case MEDIUM -> new JsonPrimitive("medium");
            case HIGH -> new JsonPrimitive("high");
            // Ollama has no level between high and max
            case EXTRA_HIGH -> new JsonPrimitive("high");
            // gpt-oss only accepts low, medium and high
            case MAX -> new JsonPrimitive(OllamaModelDataRetriever.usesNamedThinkingLevels(modelName) ? "high" : "max");
        };
    }

    private static void addAssistantTurns(RequestState state, int inputIndex, JsonArray messages) {
        for (AssistantTurn turn : state.assistantTurns) {
            if (turn.inputIndex() == inputIndex) {
                messages.add(turn.message().deepCopy());
            }
        }
    }

    private static void addConversationMessages(InputConversationContent conversationContent, JsonArray messages) {
        if (conversationContent.getConversation() == null) {
            return;
        }
        JsonArray historyImages = new JsonArray();
        Map<String, String> toolNamesByCallId = new HashMap<>();
        JsonObject lastAssistantMessage = null;
        for (ModelContent modelContent : conversationContent.getConversation().getConversation()) {
            switch (modelContent) {
                case InputTextContent textContent -> {
                    messages.add(createMessage("user", textContent.getText(), historyImages));
                    lastAssistantMessage = null;
                }
                case InputImageContent imageContent -> {
                    addImage(historyImages, imageContent.getImageBase64());
                }
                case OutputTextContent textContent -> {
                    lastAssistantMessage = createMessage("assistant", textContent.getText(), null);
                    messages.add(lastAssistantMessage);
                }
                case ToolCallContent toolCall -> {
                    // Tool calls made in the same step as a text response belong to the same assistant message
                    if (lastAssistantMessage == null) {
                        lastAssistantMessage = createMessage("assistant", "", null);
                        messages.add(lastAssistantMessage);
                    }
                    if (!lastAssistantMessage.has("tool_calls")) {
                        lastAssistantMessage.add("tool_calls", new JsonArray());
                    }
                    lastAssistantMessage.getAsJsonArray("tool_calls").add(toolCallToOllamaJson(toolCall));
                    toolNamesByCallId.put(toolCall.getToolUseId(), toolCall.getName());
                }
                case ToolCallResponseContent toolCallResponse -> {
                    messages.add(toolCallResponseToOllamaJson(toolCallResponse, toolNamesByCallId));
                    lastAssistantMessage = null;
                }
                default -> {}
            }
        }
    }

    private static JsonObject createMessage(String role, String content, JsonArray pendingImages) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        if (pendingImages != null && !pendingImages.isEmpty()) {
            msg.add("images", pendingImages.deepCopy());
            pendingImages.asList().clear();
        }
        return msg;
    }

    private static void appendImages(JsonObject message, JsonArray pendingImages) {
        if (!message.has("images")) {
            message.add("images", new JsonArray());
        }
        message.getAsJsonArray("images").addAll(pendingImages);
        pendingImages.asList().clear();
    }

    /**
     * Ollama decodes images as plain base64, so data URIs (which InputImageContent produces) must be stripped.
     */
    private static void addImage(JsonArray images, @Nullable String base64Image) {
        if (base64Image == null) {
            return;
        }
        if (base64Image.startsWith("data:")) {
            int commaIdx = base64Image.indexOf(',');
            if (commaIdx >= 0) {
                base64Image = base64Image.substring(commaIdx + 1);
            }
        }
        images.add(base64Image);
    }

    private static String skillToSystemPrompt(SkillData skill) {
        return "<skill name=\"" + skill.getSkillName() + "\" description=\"" + skill.getSkillDescription() + "\">\n"
                + skill.getSkillText() + "\n</skill>";
    }

    private static JsonObject toolCallToOllamaJson(ToolCallContent toolCall) {
        JsonObject functionObj = new JsonObject();
        functionObj.addProperty("name", toolCall.getName());
        functionObj.add(
                "arguments",
                toolCall.getToolArguments() == null
                        ? new JsonObject()
                        : toolCall.getToolArguments().deepCopy());
        JsonObject toolCallObj = new JsonObject();
        toolCallObj.addProperty("id", toolCall.getToolUseId());
        toolCallObj.add("function", functionObj);
        return toolCallObj;
    }

    private static JsonObject toolCallResponseToOllamaJson(
            ToolCallResponseContent toolCallResponse, Map<String, String> toolNamesByCallId) {
        String responseContent = toolCallResponse.getResponseContent();
        if (responseContent == null) {
            responseContent = toolCallResponse.isSuccessful() ? "" : "The tool call failed.";
        }
        // The called tool is null when the model asked for a tool that does not exist
        String toolName = toolCallResponse.getCalledTool() != null
                ? toolCallResponse.getCalledTool().getName()
                : toolNamesByCallId.get(toolCallResponse.getToolUseId());

        JsonObject toolCallResponseJson = new JsonObject();
        toolCallResponseJson.addProperty("role", "tool");
        toolCallResponseJson.addProperty("content", responseContent);
        if (toolName != null) {
            toolCallResponseJson.addProperty("tool_name", toolName);
        }
        if (toolCallResponse.getToolUseId() != null) {
            toolCallResponseJson.addProperty("tool_call_id", toolCallResponse.getToolUseId());
        }
        return toolCallResponseJson;
    }

    private static JsonObject toolArgumentsToJson(@Nullable JsonElement arguments) {
        if (arguments == null || arguments.isJsonNull()) {
            return new JsonObject();
        }
        if (arguments.isJsonObject()) {
            return arguments.getAsJsonObject();
        }
        // Some Ollama compatible servers send the arguments as an encoded JSON string
        if (arguments.isJsonPrimitive() && arguments.getAsJsonPrimitive().isString()) {
            try {
                JsonElement parsed = JsonParser.parseString(arguments.getAsString());
                if (parsed.isJsonObject()) {
                    return parsed.getAsJsonObject();
                }
            } catch (JsonParseException e) {
                LOGGER.log(Level.WARNING, "Could not parse Ollama tool call arguments: {0}", arguments);
            }
        }
        return new JsonObject();
    }

    private static JsonObject toolContentToOllamaJson(InputToolContent toolContent) {
        JsonObject json = new JsonObject();
        json.addProperty("type", "function");

        JsonObject functionObj = new JsonObject();
        functionObj.addProperty("name", toolContent.getTool().getName());
        functionObj.addProperty("description", toolContent.getTool().getDescription());

        JsonObject parametersObj = new JsonObject();
        parametersObj.addProperty("type", "object");

        JsonObject propertiesObj = new JsonObject();
        JsonArray requiredPropsArr = new JsonArray();

        toolContent.getTool().getArguments().forEach(argument -> {
            JsonObject propertyDescObj = new JsonObject();
            String typeName = TYPE_TO_OLLAMA_TYPE_NAME.getOrDefault(argument.getType(), "string");
            propertyDescObj.addProperty("type", typeName);
            propertyDescObj.addProperty("description", argument.getDescription());
            propertiesObj.add(argument.getName(), propertyDescObj);

            if (argument.isRequired()) {
                requiredPropsArr.add(argument.getName());
            }
        });

        parametersObj.add("properties", propertiesObj);
        parametersObj.add("required", requiredPropsArr);
        functionObj.add("parameters", parametersObj);
        json.add("function", functionObj);

        return json;
    }

    private static @Nullable String getString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static long getLong(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsLong() : 0L;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ModelClient<?, ?>> {
        @Override
        public @NonNull String getDisplayName() {
            return "Ollama Client";
        }
    }
}
