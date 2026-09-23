package io.ohmvir.plugins.ollamaclient;

import static java.util.Map.entry;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import hudson.Extension;
import hudson.model.Descriptor;
import io.ohmvir.plugins.jenkinsaisynapse.api.client.ModelClient;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelFinishReason;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.*;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.NonNull;

@Extension
public class OllamaClient extends ModelClient<OllamaModelSettings, OllamaClientSettings> {

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

    private static final Map<String, ModelFinishReason> OLLAMA_FINISH_REASON_TO_MODEL_API = Map.of(
            "stop", ModelFinishReason.STOP,
            "length", ModelFinishReason.TOKEN_CAP,
            "unload", ModelFinishReason.TOKEN_CAP);

    @Override
    public List<ModelOutput> takeStepImpl(
            ModelData modelData,
            OllamaModelSettings configuration,
            OllamaClientSettings clientConfiguration,
            ModelRequest request,
            List<ModelInput> turnInputs) {

        JsonObject apiReq = new JsonObject();
        JsonObject options = new JsonObject();
        JsonArray messages = new JsonArray();
        JsonArray tools = new JsonArray();
        JsonArray pendingImages = new JsonArray();

        apiReq.addProperty("model", configuration.getModelName());
        apiReq.addProperty("stream", false);

        if (clientConfiguration.getKeepAliveSeconds() > 0) {
            apiReq.addProperty("keep_alive", clientConfiguration.getKeepAliveSeconds() + "s");
        }

        turnInputs.forEach(input -> {
            switch (input) {
                case InputImageContent imageContent -> {
                    pendingImages.add(imageContent.getImageBase64());
                }

                case InputAudioContent audioContent -> {
                    String b64Audio = Base64.getEncoder().encodeToString(audioContent.getAudioData());
                    pendingImages.add(b64Audio);
                }

                case InputConversationContent conversationContent ->
                    conversationContent.getConversation().getConversation().forEach(modelContent -> {
                        if (modelContent instanceof InputTextContent textContent) {
                            messages.add(createMessage("user", textContent.getText(), pendingImages));
                        } else if (modelContent instanceof OutputTextContent textContent) {
                            messages.add(createMessage("assistant", textContent.getText(), null));
                        }
                    });

                case InputTextContent textContent -> {
                    messages.add(createMessage("user", textContent.getText(), pendingImages));
                }

                case SystemPromptContent systemPromptContent -> {
                    messages.add(createMessage("system", systemPromptContent.getSystemPrompt(), null));
                }

                case InputToolContent toolContent -> {
                    tools.add(toolContentToOllamaJson(toolContent));
                }

                case ToolCallResponseContent toolCallResponse -> {
                    JsonObject toolCallResponseJson = new JsonObject();
                    toolCallResponseJson.addProperty("role", "tool");
                    toolCallResponseJson.addProperty("content", toolCallResponse.getResponseContent());
                    toolCallResponseJson.addProperty(
                            "tool_name", toolCallResponse.getCalledTool().getName());
                    messages.add(toolCallResponseJson);
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
                    options.addProperty("top_k", topKContent.getTopK());
                }

                case TopPContent topPContent -> {
                    options.addProperty("top_p", topPContent.getTopP());
                }

                case ThinkingLevelContent thinkingLevelContent -> {
                    if (thinkingLevelContent.getThinkingLevel() == ModelThinkingLevel.OFF) {
                        apiReq.addProperty("think", false);
                    } else if (thinkingLevelContent.getThinkingLevel() == ModelThinkingLevel.ON) {
                        apiReq.addProperty("think", true);
                    } else {
                        apiReq.addProperty(
                                "think",
                                thinkingLevelContent
                                        .getThinkingLevel()
                                        .toString()
                                        .toLowerCase());
                    }
                }

                default -> {}
            }
        });

        apiReq.add("messages", messages);
        if (options.size() > 0) {
            apiReq.add("options", options);
        }
        if (tools.size() > 0) {
            apiReq.add("tools", tools);
        }

        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(clientConfiguration.getTimeoutSeconds()))
                .build()) {

            String baseUrl = SecretsUtils.getSecretText(configuration.getApiBaseUrlCredentialsId(), null);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(apiReq.toString()))
                    .build();

            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                Logger.getLogger(OllamaClient.class.getName())
                        .log(Level.SEVERE, "Ollama API returned HTTP {0}: {1}", new Object[] {
                            response.statusCode(), response.body()
                        });
                return List.of();
            }

            JsonObject resJson = new Gson().fromJson(response.body(), JsonObject.class);
            JsonObject messageObj =
                    resJson.has("message") && !resJson.get("message").isJsonNull()
                            ? resJson.getAsJsonObject("message")
                            : new JsonObject();

            List<ModelOutput> outputs = new ArrayList<>();

            request.getOutputClasses().forEach(outputClass -> {
                switch (outputClass) {
                    case Class<?> c
                    when c == FinishReasonContent.class -> {
                        if (resJson.has("done_reason")
                                && !resJson.get("done_reason").isJsonNull()) {
                            String doneReason = resJson.get("done_reason").getAsString();
                            outputs.add(new FinishReasonContent(OLLAMA_FINISH_REASON_TO_MODEL_API.getOrDefault(
                                    doneReason, ModelFinishReason.STOP)));
                        }
                    }
                    case Class<?> c
                    when c == OutputTextContent.class -> {
                        if (messageObj.has("content")
                                && !messageObj.get("content").isJsonNull()) {
                            outputs.add(new OutputTextContent(
                                    messageObj.get("content").getAsString()));
                        }
                    }
                    case Class<?> c
                    when c == ThinkingContent.class -> {
                        if (messageObj.has("thinking")
                                && !messageObj.get("thinking").isJsonNull()) {
                            outputs.add(new ThinkingContent(
                                    messageObj.get("thinking").getAsString()));
                        }
                    }
                    case Class<?> c
                    when c == TokenUtilizationContent.class -> {
                        int promptTokens = resJson.has("prompt_eval_count")
                                ? resJson.get("prompt_eval_count").getAsInt()
                                : 0;
                        int evalTokens = resJson.has("eval_count")
                                ? resJson.get("eval_count").getAsInt()
                                : 0;
                        outputs.add(new TokenUtilizationContent(promptTokens, evalTokens, 0));
                    }
                    case Class<?> c
                    when c == ToolCallContent.class -> {
                        if (messageObj.has("tool_calls")
                                && messageObj.get("tool_calls").isJsonArray()) {
                            messageObj.getAsJsonArray("tool_calls").forEach(jsonElement -> {
                                JsonObject fnObj = jsonElement.getAsJsonObject().getAsJsonObject("function");
                                String toolName = fnObj.get("name").getAsString();
                                JsonObject args =
                                        fnObj.has("arguments") ? fnObj.getAsJsonObject("arguments") : new JsonObject();
                                outputs.add(
                                        new ToolCallContent(UUID.randomUUID().toString(), args, toolName));
                            });
                        }
                    }
                    default -> {}
                }
            });

            return outputs;
        } catch (IOException | InterruptedException e) {
            Logger.getLogger(OllamaClient.class.getName()).log(Level.SEVERE, e.getMessage(), e);
            return List.of();
        }
    }

    private JsonObject createMessage(String role, String content, JsonArray pendingImages) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        if (pendingImages != null && pendingImages.size() > 0) {
            msg.add("images", pendingImages.deepCopy());
            pendingImages.asList().clear();
        }
        return msg;
    }

    private JsonObject toolContentToOllamaJson(InputToolContent toolContent) {
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

    @Extension
    public static class DescriptorImpl extends Descriptor<ModelClient<?, ?>> {
        @Override
        public @NonNull String getDisplayName() {
            return "Ollama Client";
        }
    }
}
