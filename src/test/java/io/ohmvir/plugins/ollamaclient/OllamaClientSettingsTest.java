package io.ohmvir.plugins.ollamaclient;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OllamaClientSettingsTest {
    @ParameterizedTest
    @CsvSource({
        "http://localhost:11434, http://localhost:11434/api/chat",
        "http://localhost:11434/, http://localhost:11434/api/chat",
        "http://localhost:11434///, http://localhost:11434/api/chat",
        "http://localhost:11434/api, http://localhost:11434/api/chat",
        "http://localhost:11434/api/, http://localhost:11434/api/chat",
        "'  https://ollama.example.com/proxy/  ', https://ollama.example.com/proxy/api/chat",
    })
    void buildsApiUris(String baseUrl, String expected) {
        assertEquals(URI.create(expected), OllamaClientSettings.buildApiUri(baseUrl, "/api/chat"));
    }

    @Test
    void validSettingsAreAccepted() throws Exception {
        OllamaClientSettings settings = new OllamaClientSettings(60L, 120L, "url-id", true, "cf-id", "cf-secret");

        assertEquals(60L, settings.getKeepAliveSeconds());
        assertEquals(120L, settings.getTimeoutSeconds());
        assertEquals("url-id", settings.getApiBaseUrlCredentialsId());
        assertEquals("cf-id", settings.getCloudflareAccessClientIdCredentialId());
        assertEquals("cf-secret", settings.getCloudflareAccessClientSecretCredentialId());
    }

    @Test
    void cloudflareCredentialsAreOptionalWhenCloudflareIsDisabled() {
        assertDoesNotThrow(() -> new OllamaClientSettings(0L, 0L, "url-id", false, null, null));
    }

    @Test
    void invalidSettingsAreRejectedWithFormExceptions() {
        // Null ids used to throw NullPointerException instead of a form error
        assertThrows(Descriptor.FormException.class, () -> new OllamaClientSettings(0L, 0L, null, false, null, null));
        assertThrows(Descriptor.FormException.class, () -> new OllamaClientSettings(0L, 0L, " ", false, null, null));
        assertThrows(
                Descriptor.FormException.class,
                () -> new OllamaClientSettings(0L, 0L, "url-id", true, null, "cf-secret"));
        assertThrows(
                Descriptor.FormException.class, () -> new OllamaClientSettings(0L, 0L, "url-id", true, "cf-id", ""));
        assertThrows(
                Descriptor.FormException.class, () -> new OllamaClientSettings(-1L, 0L, "url-id", false, null, null));
        assertThrows(
                Descriptor.FormException.class, () -> new OllamaClientSettings(0L, -1L, "url-id", false, null, null));
    }

    @Test
    void cloudflareCredentialChecksOnlyApplyWhenEnabled() throws Exception {
        OllamaClientSettings settings = new OllamaClientSettings(0L, 0L, "url-id", false, null, null);

        assertEquals(FormValidation.Kind.OK, settings.doCheckCloudflareAccessClientIdCredentialId(null, false).kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckCloudflareAccessClientIdCredentialId(null, true).kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckCloudflareAccessClientIdCredentialId("", true).kind);
        assertEquals(FormValidation.Kind.OK, settings.doCheckCloudflareAccessClientIdCredentialId("cf-id", true).kind);

        assertEquals(
                FormValidation.Kind.OK, settings.doCheckCloudflareAccessClientSecretCredentialId(null, false).kind);
        assertEquals(
                FormValidation.Kind.ERROR, settings.doCheckCloudflareAccessClientSecretCredentialId(null, true).kind);
        assertEquals(
                FormValidation.Kind.OK,
                settings.doCheckCloudflareAccessClientSecretCredentialId("cf-secret", true).kind);
    }
}
