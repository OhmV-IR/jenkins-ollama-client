package io.ohmvir.plugins.ollamaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.ohmvir.plugins.jenkinsaisynapse.api.ModelConversation;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelFinishReason;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.skills.SkillData;
import java.awt.image.BufferedImage;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OllamaClientTest {
    private static final Set<Class<? extends ModelOutput>> ALL_OUTPUTS = Set.of(
            OutputTextContent.class,
            ThinkingContent.class,
            FinishReasonContent.class,
            TokenUtilizationContent.class,
            ToolCallContent.class);

    private static JsonObject build(List<ModelInput> inputs) {
        return build(inputs, new OllamaClient.RequestState());
    }

    private static JsonObject build(List<ModelInput> inputs, OllamaClient.RequestState state) {
        return OllamaClient.buildChatRequest("llama3", 0L, inputs, state);
    }

    private static JsonArray messages(JsonObject request) {
        return request.getAsJsonArray("messages");
    }

    private static JsonObject message(JsonObject request, int index) {
        return messages(request).get(index).getAsJsonObject();
    }

    private static InputImageContent image() {
        return new InputImageContent(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB));
    }

    /** ModelConversation persists itself to JENKINS_HOME on every change, which unit tests do not have. */
    private static ModelConversation conversation(io.ohmvir.plugins.jenkinsaisynapse.api.ModelContent... contents)
            throws Exception {
        ModelConversation conversation = new ModelConversation() {
            @Override
            public void save() {}
        };
        for (var content : contents) {
            conversation.addContent(content);
        }
        return conversation;
    }

    @Test
    void simpleRequestOnlyContainsRequiredFields() {
        JsonObject request = build(List.of(new InputTextContent("Hello")));

        assertEquals("llama3", request.get("model").getAsString());
        assertFalse(request.get("stream").getAsBoolean());
        assertEquals(1, messages(request).size());
        assertEquals("user", message(request, 0).get("role").getAsString());
        assertEquals("Hello", message(request, 0).get("content").getAsString());
        assertFalse(message(request, 0).has("images"));
        assertFalse(request.has("options"));
        assertFalse(request.has("tools"));
        assertFalse(request.has("think"));
        assertFalse(request.has("keep_alive"));
    }

    @Test
    void keepAliveIsSentAsDuration() {
        JsonObject request = OllamaClient.buildChatRequest(
                "llama3", 300L, List.of(new InputTextContent("Hello")), new OllamaClient.RequestState());

        assertEquals("300s", request.get("keep_alive").getAsString());
    }

    @Test
    void systemPromptsAndSkillsAreMergedIntoLeadingSystemMessage() {
        SkillData skill = new SkillData("reviewer", "Reviews code", null, null, null, null, "Look for bugs.", Map.of());
        // ModelRequestExecutionStep adds the user text before the system prompt
        JsonObject request = build(List.of(
                new InputTextContent("Review this"),
                new SystemPromptContent("You are helpful."),
                new InputSkillContent(skill)));

        assertEquals(2, messages(request).size());
        assertEquals("system", message(request, 0).get("role").getAsString());
        assertEquals(
                "You are helpful.\n\n<skill name=\"reviewer\" description=\"Reviews code\">\nLook for bugs.\n</skill>",
                message(request, 0).get("content").getAsString());
        assertEquals("user", message(request, 1).get("role").getAsString());
    }

    @Test
    void imagesAreSentAsPlainBase64() {
        JsonObject request = build(List.of(image(), new InputTextContent("What is this?")));

        JsonArray images = message(request, 0).getAsJsonArray("images");
        assertEquals(1, images.size());
        String encoded = images.get(0).getAsString();
        assertFalse(encoded.startsWith("data:"), "Ollama rejects data URIs: " + encoded);
        byte[] decoded = Base64.getDecoder().decode(encoded);
        assertEquals("PNG", new String(decoded, 1, 3));
    }

    @Test
    void imagesAfterTheTextAreAttachedToTheLastUserMessage() {
        JsonObject request = build(List.of(new InputTextContent("What is this?"), image()));

        assertEquals(1, messages(request).size());
        assertEquals(1, message(request, 0).getAsJsonArray("images").size());
    }

    @Test
    void imageOnlyRequestCreatesAUserMessage() {
        JsonObject request = build(List.of(image()));

        assertEquals(1, messages(request).size());
        assertEquals("user", message(request, 0).get("role").getAsString());
        assertEquals("", message(request, 0).get("content").getAsString());
        assertEquals(1, message(request, 0).getAsJsonArray("images").size());
    }

    @Test
    void imagesAreOnlyAttachedOnce() {
        JsonObject request = build(List.of(image(), new InputTextContent("first"), new InputTextContent("second")));

        assertTrue(message(request, 0).has("images"));
        assertFalse(message(request, 1).has("images"));
    }

    @Test
    void audioIsSentAsBase64() {
        byte[] audio = {1, 2, 3, 4};
        JsonObject request = build(List.of(new InputAudioContent(audio), new InputTextContent("Transcribe")));

        assertEquals(
                Base64.getEncoder().encodeToString(audio),
                message(request, 0).getAsJsonArray("images").get(0).getAsString());
    }

    @Test
    void samplingOptionsAreMapped() {
        JsonObject request = build(List.of(
                new InputTextContent("Hello"),
                new MaxOutputTokensContent(128),
                new TemperatureContent(0.5),
                new TopPContent(0.9),
                new TopKContent(40.0),
                new StopSequencesContent(List.of("END", "STOP"))));

        JsonObject options = request.getAsJsonObject("options");
        assertEquals(128, options.get("num_predict").getAsLong());
        assertEquals(0.5, options.get("temperature").getAsDouble());
        assertEquals(0.9, options.get("top_p").getAsDouble());
        assertEquals("40", options.get("top_k").toString(), "top_k is an integer option");
        assertEquals(JsonParser.parseString("[\"END\",\"STOP\"]"), options.get("stop"));
    }

    @ParameterizedTest
    @CsvSource({
        "OFF, llama3, false",
        "ON, llama3, true",
        "LOW, llama3, \"low\"",
        "MEDIUM, llama3, \"medium\"",
        "HIGH, llama3, \"high\"",
        "EXTRA_HIGH, llama3, \"high\"",
        "MAX, qwen3, \"max\"",
        "MAX, gpt-oss:20b, \"high\"",
    })
    void thinkingLevelsMapToValidOllamaValues(ModelThinkingLevel level, String modelName, String expectedJson) {
        JsonPrimitive think = OllamaClient.thinkingLevelToOllamaJson(level, modelName);

        assertEquals(JsonParser.parseString(expectedJson), think);
    }

    @Test
    void thinkingLevelIsSentAsThink() {
        JsonObject request =
                build(List.of(new InputTextContent("Hello"), new ThinkingLevelContent(ModelThinkingLevel.OFF)));

        assertFalse(request.get("think").getAsBoolean());
    }

    @Test
    void toolsAreSentAsFunctionSchemas() throws Exception {
        JsonObject request =
                build(List.of(new InputTextContent("Hello"), new InputToolContent(new TestTools.ForecastTool())));

        JsonObject tool = request.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        JsonObject function = tool.getAsJsonObject("function");
        assertEquals("getForecast", function.get("name").getAsString());
        assertEquals("Gets the forecast for a city", function.get("description").getAsString());
        JsonObject parameters = function.getAsJsonObject("parameters");
        assertEquals("object", parameters.get("type").getAsString());
        assertEquals(
                "string",
                parameters
                        .getAsJsonObject("properties")
                        .getAsJsonObject("city")
                        .get("type")
                        .getAsString());
        assertEquals(
                "integer",
                parameters
                        .getAsJsonObject("properties")
                        .getAsJsonObject("days")
                        .get("type")
                        .getAsString());
        assertEquals(JsonParser.parseString("[\"city\"]"), parameters.get("required"));
    }

    @Test
    void conversationHistoryIsConvertedToOllamaMessages() throws Exception {
        JsonObject args = JsonParser.parseString("{\"city\":\"Paris\"}").getAsJsonObject();
        ModelConversation history = conversation(
                new SystemPromptContent("Old system prompt"),
                new InputTextContent("What's the weather?"),
                new ToolCallContent("call_1", args, "getWeather"),
                new ToolCallResponseContent("call_1", true, "Sunny", null),
                new OutputTextContent("It is sunny in Paris."),
                new FinishReasonContent(ModelFinishReason.STOP));

        JsonObject request =
                build(List.of(image(), new InputConversationContent(history), new InputTextContent("Thanks!")));

        JsonArray messages = messages(request);
        assertEquals(5, messages.size(), messages.toString());

        JsonObject question = messages.get(0).getAsJsonObject();
        assertEquals("user", question.get("role").getAsString());
        assertEquals("What's the weather?", question.get("content").getAsString());
        assertFalse(question.has("images"), "Images from the current turn must not leak into the history");

        JsonObject toolCallMessage = messages.get(1).getAsJsonObject();
        assertEquals("assistant", toolCallMessage.get("role").getAsString());
        JsonObject toolCall =
                toolCallMessage.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", toolCall.get("id").getAsString());
        assertEquals(
                "getWeather", toolCall.getAsJsonObject("function").get("name").getAsString());
        assertEquals(args, toolCall.getAsJsonObject("function").get("arguments"));

        JsonObject toolResult = messages.get(2).getAsJsonObject();
        assertEquals("tool", toolResult.get("role").getAsString());
        assertEquals("Sunny", toolResult.get("content").getAsString());
        assertEquals("getWeather", toolResult.get("tool_name").getAsString());
        assertEquals("call_1", toolResult.get("tool_call_id").getAsString());

        assertEquals("assistant", messages.get(3).getAsJsonObject().get("role").getAsString());
        assertEquals(
                "It is sunny in Paris.",
                messages.get(3).getAsJsonObject().get("content").getAsString());

        JsonObject followUp = messages.get(4).getAsJsonObject();
        assertEquals("Thanks!", followUp.get("content").getAsString());
        assertEquals(1, followUp.getAsJsonArray("images").size());
    }

    @Test
    void toolCallsInTheSameStepAsTextShareTheAssistantMessage() throws Exception {
        ModelConversation history = conversation(
                new InputTextContent("Weather?"),
                new OutputTextContent("Let me check."),
                new ToolCallContent("call_1", new JsonObject(), "getWeather"));

        JsonObject request = build(List.of(new InputConversationContent(history)));

        assertEquals(2, messages(request).size());
        JsonObject assistant = message(request, 1);
        assertEquals("Let me check.", assistant.get("content").getAsString());
        assertEquals(1, assistant.getAsJsonArray("tool_calls").size());
    }

    @Test
    void failedToolCallForUnknownToolIsReportedToTheModel() {
        OllamaClient.RequestState state = new OllamaClient.RequestState();
        state.toolNamesByCallId.put("call_7", "doesNotExist");

        // ModelRequest passes a null tool and null content when the tool is missing
        JsonObject request = build(List.of(new ToolCallResponseContent("call_7", false, null, null)), state);

        JsonObject toolResult = message(request, 0);
        assertEquals("tool", toolResult.get("role").getAsString());
        assertEquals("The tool call failed.", toolResult.get("content").getAsString());
        assertEquals("doesNotExist", toolResult.get("tool_name").getAsString());
        assertEquals("call_7", toolResult.get("tool_call_id").getAsString());
    }

    @Test
    void toolReturningNothingSendsEmptyContent() throws Exception {
        JsonObject request =
                build(List.of(new ToolCallResponseContent("call_8", true, null, new TestTools.WeatherTool())));

        assertEquals("", message(request, 0).get("content").getAsString());
        assertEquals("getWeather", message(request, 0).get("tool_name").getAsString());
    }

    @Test
    void assistantToolCallsAreReplayedBeforeTheirResponses() throws Exception {
        OllamaClient.RequestState state = new OllamaClient.RequestState();
        List<ModelInput> firstStepInputs =
                List.of(new SystemPromptContent("Be brief."), new InputTextContent("Weather in Paris?"));
        JsonObject toolCallResponse = JsonParser.parseString("""
                {"message":{"role":"assistant","content":"","thinking":"Need the tool",
                 "tool_calls":[{"function":{"name":"getWeather","arguments":{"city":"Paris"}}}]},
                 "done":true,"done_reason":"stop"}""").getAsJsonObject();
        List<ModelOutput> outputs =
                OllamaClient.parseChatResponse(toolCallResponse, ALL_OUTPUTS, state, firstStepInputs.size());
        ToolCallContent toolCall = outputs.stream()
                .filter(ToolCallContent.class::isInstance)
                .map(ToolCallContent.class::cast)
                .findFirst()
                .orElseThrow();

        List<ModelInput> secondStepInputs = List.of(
                firstStepInputs.get(0),
                firstStepInputs.get(1),
                new ToolCallResponseContent(
                        toolCall.getToolUseId(), true, "Sunny in Paris", new TestTools.WeatherTool()));
        JsonObject request = build(secondStepInputs, state);

        JsonArray messages = messages(request);
        assertEquals(4, messages.size(), messages.toString());
        assertEquals("system", message(request, 0).get("role").getAsString());
        assertEquals("user", message(request, 1).get("role").getAsString());
        JsonObject assistant = message(request, 2);
        assertEquals("assistant", assistant.get("role").getAsString());
        assertEquals("Need the tool", assistant.get("thinking").getAsString());
        JsonObject replayedCall = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals(toolCall.getToolUseId(), replayedCall.get("id").getAsString());
        JsonObject toolResult = message(request, 3);
        assertEquals("tool", toolResult.get("role").getAsString());
        assertEquals(toolCall.getToolUseId(), toolResult.get("tool_call_id").getAsString());
    }

    @Test
    void parsesTextThinkingFinishReasonAndUsage() {
        OllamaClient.RequestState state = new OllamaClient.RequestState();
        JsonObject response = JsonParser.parseString("""
                {"model":"llama3","message":{"role":"assistant","content":"Hello!","thinking":"Greet them"},
                 "done":true,"done_reason":"stop","prompt_eval_count":12,"eval_count":34}""").getAsJsonObject();

        List<ModelOutput> outputs = OllamaClient.parseChatResponse(response, ALL_OUTPUTS, state, 1);

        assertEquals(4, outputs.size(), outputs.toString());
        assertEquals(
                "Greet them",
                assertInstanceOf(ThinkingContent.class, outputs.get(0)).getThinking());
        assertEquals(
                "Hello!",
                assertInstanceOf(OutputTextContent.class, outputs.get(1)).getText());
        assertEquals(
                ModelFinishReason.STOP,
                assertInstanceOf(FinishReasonContent.class, outputs.get(2)).getReason());
        TokenUtilizationContent usage = assertInstanceOf(TokenUtilizationContent.class, outputs.get(3));
        assertEquals(12, usage.getInputTokensUsed());
        assertEquals(34, usage.getOutputTokensUsed());
        assertFalse(state.lastStepHadToolCalls);
    }

    @Test
    void onlyRequestedOutputsAreReturned() {
        JsonObject response = JsonParser.parseString("""
                {"message":{"role":"assistant","content":"Hello!","thinking":"hmm"},
                 "done":true,"done_reason":"stop","prompt_eval_count":1,"eval_count":2}""").getAsJsonObject();

        List<ModelOutput> outputs = OllamaClient.parseChatResponse(
                response, Set.of(OutputTextContent.class), new OllamaClient.RequestState(), 1);

        assertEquals(1, outputs.size());
        assertInstanceOf(OutputTextContent.class, outputs.get(0));
    }

    @Test
    void toolCallsAreAlwaysReturnedAndKeepOllamaIds() {
        OllamaClient.RequestState state = new OllamaClient.RequestState();
        JsonObject response = JsonParser.parseString("""
                {"message":{"role":"assistant","content":"","tool_calls":[
                  {"id":"call_9","function":{"index":0,"name":"getWeather","arguments":{"city":"Paris"}}},
                  {"function":{"index":1,"name":"getWeather","arguments":"{\\"city\\":\\"Rome\\"}"}}]},
                 "done":true,"done_reason":"stop"}""").getAsJsonObject();

        // ToolCallContent is deliberately not requested
        List<ModelOutput> outputs = OllamaClient.parseChatResponse(
                response, Set.of(OutputTextContent.class, FinishReasonContent.class), state, 1);

        assertEquals(3, outputs.size(), "No empty text output: " + outputs);
        ToolCallContent first = assertInstanceOf(ToolCallContent.class, outputs.get(0));
        assertEquals("call_9", first.getToolUseId());
        assertEquals("getWeather", first.getName());
        assertEquals("Paris", first.getToolArguments().get("city").getAsString());
        ToolCallContent second = assertInstanceOf(ToolCallContent.class, outputs.get(1));
        assertFalse(second.getToolUseId().isBlank());
        assertNotEquals(first.getToolUseId(), second.getToolUseId());
        assertEquals("Rome", second.getToolArguments().get("city").getAsString());
        assertEquals(
                ModelFinishReason.TOOL_CALLS,
                assertInstanceOf(FinishReasonContent.class, outputs.get(2)).getReason());

        assertTrue(state.lastStepHadToolCalls);
        assertEquals("getWeather", state.toolNamesByCallId.get(second.getToolUseId()));
        JsonArray replayed = state.assistantTurns.get(0).message().getAsJsonArray("tool_calls");
        assertEquals(
                second.getToolUseId(),
                replayed.get(1).getAsJsonObject().get("id").getAsString());
    }

    @Test
    void toolCallsWithoutNameAreIgnored() {
        JsonObject response = JsonParser.parseString(
                        "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"function\":{}}]}}")
                .getAsJsonObject();
        OllamaClient.RequestState state = new OllamaClient.RequestState();

        List<ModelOutput> outputs = OllamaClient.parseChatResponse(response, ALL_OUTPUTS, state, 1);

        assertTrue(outputs.stream().noneMatch(ToolCallContent.class::isInstance));
        assertFalse(state.lastStepHadToolCalls);
    }

    @ParameterizedTest
    @CsvSource({"stop, STOP", "length, TOKEN_CAP", "load, STOP", "unload, STOP", "something_new, STOP"})
    void finishReasonsAreMapped(String doneReason, ModelFinishReason expected) {
        JsonObject response = JsonParser.parseString(
                        "{\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"done_reason\":\"" + doneReason
                                + "\"}")
                .getAsJsonObject();

        List<ModelOutput> outputs = OllamaClient.parseChatResponse(
                response, Set.of(FinishReasonContent.class), new OllamaClient.RequestState(), 1);

        assertEquals(
                expected,
                assertInstanceOf(FinishReasonContent.class, outputs.get(0)).getReason());
    }

    @Test
    void missingFieldsDoNotFail() {
        List<ModelOutput> outputs =
                OllamaClient.parseChatResponse(new JsonObject(), ALL_OUTPUTS, new OllamaClient.RequestState(), 1);

        assertEquals(1, outputs.size());
        TokenUtilizationContent usage = assertInstanceOf(TokenUtilizationContent.class, outputs.get(0));
        assertEquals(0, usage.getInputTokensUsed());
        assertEquals(0, usage.getOutputTokensUsed());
    }
}
