package io.ohmvir.plugins.ollamaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.ohmvir.plugins.jenkinsaisynapse.api.input.InputTextContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.ModelRequest;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.ThinkingLevelContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.OutputTextContent;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.ThinkingContent;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OllamaModelDataRetrieverIntegrationTest {
    private MockOllamaServer ollama;

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        ollama = new MockOllamaServer();
        OllamaClientIntegrationTest.addStringCredential("ollama-url", ollama.baseUrl() + "/");
        OllamaClientSettings.get().setApiBaseUrlCredentialsId("ollama-url");
    }

    @AfterEach
    void tearDown() {
        ollama.close();
    }

    @Test
    void retrievesModelDataFromShowEndpoint() throws Exception {
        ollama.enqueue(200, "{\"capabilities\":[\"completion\",\"thinking\"]}");

        ModelData data = new OllamaModelDataRetriever().retrieveFromConfiguration(new OllamaModelSettings("qwen3"));

        assertTrue(data.supportsThinking());
        MockOllamaServer.RecordedRequest sent = ollama.requests().get(0);
        assertEquals("POST", sent.method());
        assertEquals("/api/show", sent.path());
        assertEquals("qwen3", sent.json().get("model").getAsString());
        assertFalse(sent.json().has("verbose"), "verbose returns the full tokenizer and is not needed");
    }

    @Test
    void modelNamesAreJsonEscaped() throws Exception {
        ollama.enqueue(200, "{\"capabilities\":[\"completion\"]}");

        new OllamaModelDataRetriever().retrieveFromConfiguration(new OllamaModelSettings("we\"ird\\name"));

        assertEquals(
                "we\"ird\\name", ollama.requests().get(0).json().get("model").getAsString());
    }

    @Test
    void unknownModelThrowsIOException() {
        ollama.enqueue(404, "{\"error\":\"model 'missing' not found\"}");

        IOException e = assertThrows(
                IOException.class,
                () -> new OllamaModelDataRetriever().retrieveFromConfiguration(new OllamaModelSettings("missing")));
        assertTrue(e.getMessage().contains("404"), e.getMessage());
    }

    @Test
    void unconfiguredBaseUrlThrowsIOException() {
        OllamaClientSettings.get().setApiBaseUrlCredentialsId(null);

        assertThrows(
                IOException.class,
                () -> new OllamaModelDataRetriever().retrieveFromConfiguration(new OllamaModelSettings("llama3")));
        assertTrue(ollama.requests().isEmpty());
    }

    @Test
    void retrievedModelsCanServeTextAndThinkingRequests() throws Exception {
        ollama.enqueue(200, "{\"capabilities\":[\"completion\",\"thinking\"]}");
        ModelData data = new OllamaModelDataRetriever().retrieveFromConfiguration(new OllamaModelSettings("qwen3"));

        ModelRequest textRequest = new ModelRequest();
        textRequest.addInput(new InputTextContent("Hello"));
        textRequest.requestOutputType(OutputTextContent.class);
        assertTrue(data.supportsRequest(textRequest), "Text input was not advertised");

        ModelRequest thinkingRequest = new ModelRequest();
        thinkingRequest.addInput(new InputTextContent("Hello"));
        thinkingRequest.addInput(new ThinkingLevelContent(ModelThinkingLevel.HIGH));
        thinkingRequest.requestOutputType(OutputTextContent.class);
        thinkingRequest.requestOutputType(ThinkingContent.class);
        assertTrue(data.supportsRequest(thinkingRequest), "Thinking capability was not advertised");
    }
}
