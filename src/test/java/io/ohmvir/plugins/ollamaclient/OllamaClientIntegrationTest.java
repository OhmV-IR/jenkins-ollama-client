package io.ohmvir.plugins.ollamaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import hudson.ExtensionList;
import hudson.util.Secret;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.*;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.ModelOutput;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.OutputTextContent;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.ModelsManagementLink;
import java.time.Duration;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OllamaClientIntegrationTest {
    private static final String TEXT_RESPONSE = """
            {"model":"llama3","message":{"role":"assistant","content":"Hello there!"},
             "done":true,"done_reason":"stop","prompt_eval_count":5,"eval_count":3}""";

    private MockOllamaServer ollama;
    private OllamaClientSettings settings;

    static void addStringCredential(String id, String secret) throws Exception {
        CredentialsProvider.lookupStores(Jenkins.get())
                .iterator()
                .next()
                .addCredentials(
                        Domain.global(),
                        new StringCredentialsImpl(CredentialsScope.GLOBAL, id, id, Secret.fromString(secret)));
    }

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        ollama = new MockOllamaServer();
        addStringCredential("ollama-url", ollama.baseUrl());
        settings = OllamaClientSettings.get();
        settings.setApiBaseUrlCredentialsId("ollama-url");
        settings.setTimeoutSeconds(30L);
    }

    @AfterEach
    void tearDown() {
        ollama.close();
    }

    private static OllamaClient client() {
        return ExtensionList.lookupSingleton(OllamaClient.class);
    }

    private static ModelRequest textRequest(String text) {
        ModelRequest request = new ModelRequest();
        request.addInput(new InputTextContent(text));
        request.requestOutputType(OutputTextContent.class);
        return request;
    }

    private List<ModelOutput> takeStep(ModelRequest request) throws Exception {
        return client().takeStep(null, new OllamaModelSettings("llama3"), settings, request, request.getModelInputs());
    }

    @Test
    void sendsChatRequestAndEndsTheLoopOnTheNextStep() throws Exception {
        ollama.enqueue(200, TEXT_RESPONSE);
        ModelRequest request = textRequest("Hi");
        request.addInput(new SystemPromptContent("Be nice"));

        List<ModelOutput> outputs = takeStep(request);

        assertEquals(1, outputs.size());
        assertEquals(
                "Hello there!",
                assertInstanceOf(OutputTextContent.class, outputs.get(0)).getText());
        MockOllamaServer.RecordedRequest sent = ollama.requests().get(0);
        assertEquals("POST", sent.method());
        assertEquals("/api/chat", sent.path());
        assertEquals("application/json", sent.headers().getFirst("Content-Type"));
        JsonObject body = sent.json();
        assertEquals("llama3", body.get("model").getAsString());
        assertEquals(
                "system",
                body.getAsJsonArray("messages")
                        .get(0)
                        .getAsJsonObject()
                        .get("role")
                        .getAsString());

        // ModelRequest#execute calls takeStep again with the same inputs, it must not hit the API again
        assertTrue(takeStep(request).isEmpty());
        assertEquals(1, ollama.requests().size());
    }

    @Test
    void executeRunsToolCallsAndSendsTheResultsBack() throws Exception {
        TestTools.WeatherTool weatherTool = new TestTools.WeatherTool();
        OllamaModelSettings config = new OllamaModelSettings("llama3");
        ModelsManagementLink.get().getModelConfigurations().add(config);
        ModelData modelData = new ModelData(config);

        ollama.enqueue(200, """
                {"message":{"role":"assistant","content":"","tool_calls":[
                  {"id":"call_1","function":{"index":0,"name":"getWeather","arguments":{"city":"Paris"}}}]},
                 "done":true,"done_reason":"stop"}""");
        ollama.enqueue(200, """
                {"message":{"role":"assistant","content":"It is sunny in Paris."},
                 "done":true,"done_reason":"stop"}""");

        ModelRequest request = textRequest("What's the weather in Paris?");
        request.attachTool(weatherTool);

        // Before the fix this never returned, as every step produced output
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> request.execute(modelData));

        List<MockOllamaServer.RecordedRequest> sent = ollama.requests();
        assertEquals(2, sent.size(), "Expected one tool call round trip and then completion");

        JsonObject secondRequest = sent.get(1).json();
        assertTrue(secondRequest.has("tools"));
        JsonArray messages = secondRequest.getAsJsonArray("messages");
        assertEquals(3, messages.size(), messages.toString());
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        JsonObject assistant = messages.get(1).getAsJsonObject();
        assertEquals("assistant", assistant.get("role").getAsString());
        assertEquals(
                "call_1",
                assistant
                        .getAsJsonArray("tool_calls")
                        .get(0)
                        .getAsJsonObject()
                        .get("id")
                        .getAsString());
        JsonObject toolResult = messages.get(2).getAsJsonObject();
        assertEquals("tool", toolResult.get("role").getAsString());
        assertEquals("Sunny in Paris", toolResult.get("content").getAsString());
        assertEquals("getWeather", toolResult.get("tool_name").getAsString());
        assertEquals("call_1", toolResult.get("tool_call_id").getAsString());

        assertTrue(request.getModelInputs().stream()
                .anyMatch(input -> input instanceof ToolCallResponseContent response && response.isSuccessful()));
    }

    @Test
    void zeroTimeoutMeansNoTimeout() throws Exception {
        // HttpClient rejects Duration.ZERO, this used to throw IllegalArgumentException
        settings.setTimeoutSeconds(0L);
        ollama.enqueue(200, TEXT_RESPONSE);

        assertEquals(1, takeStep(textRequest("Hi")).size());
    }

    @Test
    void baseUrlWithTrailingSlashOrApiSuffixIsNormalized() throws Exception {
        addStringCredential("ollama-url-api", ollama.baseUrl() + "/api/");
        settings.setApiBaseUrlCredentialsId("ollama-url-api");
        ollama.enqueue(200, TEXT_RESPONSE);

        takeStep(textRequest("Hi"));

        assertEquals("/api/chat", ollama.requests().get(0).path());
    }

    @Test
    void httpErrorsReturnNoOutputs() throws Exception {
        ollama.enqueue(404, "{\"error\":\"model 'llama3' not found\"}");

        assertTrue(takeStep(textRequest("Hi")).isEmpty());
    }

    @Test
    void invalidJsonReturnsNoOutputs() throws Exception {
        ollama.enqueue(200, "not json");

        assertTrue(takeStep(textRequest("Hi")).isEmpty());
    }

    @Test
    void unresolvableBaseUrlReturnsNoOutputsWithoutCallingOllama() throws Exception {
        settings.setApiBaseUrlCredentialsId("does-not-exist");

        assertTrue(takeStep(textRequest("Hi")).isEmpty());
        assertTrue(ollama.requests().isEmpty());
    }

    @Test
    void cloudflareAccessHeadersAreSentWhenEnabled() throws Exception {
        addStringCredential("cf-id", "client-id-value");
        addStringCredential("cf-secret", "client-secret-value");
        settings.setUsesCloudflareAccess(true);
        settings.setCloudflareAccessClientIdCredentialId("cf-id");
        settings.setCloudflareAccessClientSecretCredentialId("cf-secret");
        ollama.enqueue(200, TEXT_RESPONSE);

        takeStep(textRequest("Hi"));

        MockOllamaServer.RecordedRequest sent = ollama.requests().get(0);
        assertEquals("client-id-value", sent.headers().getFirst("CF-Access-Client-Id"));
        assertEquals("client-secret-value", sent.headers().getFirst("CF-Access-Client-Secret"));
    }

    @Test
    void missingCloudflareCredentialsDoNotBreakTheRequest() throws Exception {
        // HttpRequest.Builder#header throws on null values, which used to escape takeStep
        settings.setUsesCloudflareAccess(true);
        settings.setCloudflareAccessClientIdCredentialId("missing-id");
        settings.setCloudflareAccessClientSecretCredentialId("missing-secret");
        ollama.enqueue(200, TEXT_RESPONSE);

        assertEquals(1, takeStep(textRequest("Hi")).size());
        assertNull(ollama.requests().get(0).headers().getFirst("CF-Access-Client-Id"));
    }

    @Test
    void cloudflareHeadersAreNotSentWhenDisabled() throws Exception {
        addStringCredential("cf-id", "client-id-value");
        addStringCredential("cf-secret", "client-secret-value");
        settings.setCloudflareAccessClientIdCredentialId("cf-id");
        settings.setCloudflareAccessClientSecretCredentialId("cf-secret");
        ollama.enqueue(200, TEXT_RESPONSE);

        takeStep(textRequest("Hi"));

        assertFalse(ollama.requests().get(0).headers().containsKey("CF-Access-Client-Id"));
    }
}
