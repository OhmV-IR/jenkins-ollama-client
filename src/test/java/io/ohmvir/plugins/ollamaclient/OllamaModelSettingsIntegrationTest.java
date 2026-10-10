package io.ohmvir.plugins.ollamaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.ListBoxModel;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OllamaModelSettingsIntegrationTest {
    private MockOllamaServer ollama;
    private final OllamaModelSettings.DescriptorImpl descriptor = new OllamaModelSettings.DescriptorImpl();

    @BeforeEach
    void setUp(JenkinsRule j) throws Exception {
        ollama = new MockOllamaServer();
        OllamaClientIntegrationTest.addStringCredential("ollama-url", ollama.baseUrl());
        OllamaClientSettings.get().setApiBaseUrlCredentialsId("ollama-url");
    }

    @AfterEach
    void tearDown() {
        ollama.close();
    }

    @Test
    void listsModelsFromTagsEndpoint() {
        ollama.enqueue(200, """
                {"models":[{"name":"llama3:latest","model":"llama3:latest"},
                           {"name":"qwen3:8b"},
                           {"name":null,"model":"broken"},
                           "not an object"]}""");

        ListBoxModel models = descriptor.doFillModelNameItems(null);

        assertEquals(
                List.of("llama3:latest", "qwen3:8b"),
                models.stream().map(o -> o.value).toList());
        assertEquals("GET", ollama.requests().get(0).method());
        assertEquals("/api/tags", ollama.requests().get(0).path());
    }

    @Test
    void sendsCloudflareAccessHeaders() throws Exception {
        OllamaClientIntegrationTest.addStringCredential("cf-id", "client-id-value");
        OllamaClientIntegrationTest.addStringCredential("cf-secret", "client-secret-value");
        OllamaClientSettings settings = OllamaClientSettings.get();
        settings.setUsesCloudflareAccess(true);
        settings.setCloudflareAccessClientIdCredentialId("cf-id");
        settings.setCloudflareAccessClientSecretCredentialId("cf-secret");
        ollama.enqueue(200, "{\"models\":[]}");

        descriptor.doFillModelNameItems(null);

        assertEquals("client-id-value", ollama.requests().get(0).headers().getFirst("CF-Access-Client-Id"));
        assertEquals("client-secret-value", ollama.requests().get(0).headers().getFirst("CF-Access-Client-Secret"));
    }

    @Test
    void errorsProduceAnEmptyList() {
        ollama.enqueue(500, "{\"error\":\"boom\"}");
        ollama.enqueue(200, "not json");
        ollama.enqueue(200, "{\"error\":\"unauthorized\"}");

        assertTrue(descriptor.doFillModelNameItems(null).isEmpty());
        assertTrue(descriptor.doFillModelNameItems(null).isEmpty());
        assertTrue(descriptor.doFillModelNameItems(null).isEmpty());
    }

    @Test
    void unconfiguredBaseUrlProducesAnEmptyList() {
        OllamaClientSettings.get().setApiBaseUrlCredentialsId(null);

        assertTrue(descriptor.doFillModelNameItems(null).isEmpty());
        assertTrue(ollama.requests().isEmpty());
    }
}
