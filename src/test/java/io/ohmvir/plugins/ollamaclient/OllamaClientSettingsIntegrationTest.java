package io.ohmvir.plugins.ollamaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class OllamaClientSettingsIntegrationTest {
    private static List<String> values(ListBoxModel model) {
        return model.stream().map(option -> option.value).toList();
    }

    @Test
    void settingsSubmittedFromTheUiArePersisted(JenkinsRule j) throws Exception {
        OllamaClientIntegrationTest.addStringCredential("ollama-url", "http://localhost:11434");
        OllamaClientIntegrationTest.addStringCredential("cf-id", "id");
        OllamaClientIntegrationTest.addStringCredential("cf-secret", "secret");
        OllamaClientSettings settings = OllamaClientSettings.get();
        settings.setKeepAliveSeconds(300L);
        settings.setTimeoutSeconds(90L);
        settings.setApiBaseUrlCredentialsId("ollama-url");
        settings.setUsesCloudflareAccess(true);
        settings.setCloudflareAccessClientIdCredentialId("cf-id");
        settings.setCloudflareAccessClientSecretCredentialId("cf-secret");

        j.configRoundtrip();

        assertSettings(OllamaClientSettings.get());
        // A fresh instance reads config from disk, as happens on restart
        assertSettings(new OllamaClientSettings());
    }

    private static void assertSettings(OllamaClientSettings settings) {
        assertEquals(300L, settings.getKeepAliveSeconds());
        assertEquals(90L, settings.getTimeoutSeconds());
        assertEquals("ollama-url", settings.getApiBaseUrlCredentialsId());
        assertTrue(settings.isUsesCloudflareAccess());
        assertEquals("cf-id", settings.getCloudflareAccessClientIdCredentialId());
        assertEquals("cf-secret", settings.getCloudflareAccessClientSecretCredentialId());
    }

    @Test
    void credentialDropdownsListStringCredentials(JenkinsRule j) throws Exception {
        OllamaClientIntegrationTest.addStringCredential("ollama-url", "http://localhost:11434");
        OllamaClientSettings settings = OllamaClientSettings.get();

        assertTrue(
                values(settings.doFillApiBaseUrlCredentialsIdItems(null, null)).contains("ollama-url"));
        assertTrue(values(settings.doFillCloudflareAccessClientIdCredentialIdItems(null, null))
                .contains("ollama-url"));
        assertTrue(values(settings.doFillCloudflareAccessClientSecretCredentialIdItems(null, null))
                .contains("ollama-url"));
    }

    @Test
    void apiBaseUrlCredentialIsValidated(JenkinsRule j) throws Exception {
        OllamaClientIntegrationTest.addStringCredential("ollama-url", "http://localhost:11434");
        OllamaClientSettings settings = OllamaClientSettings.get();

        assertEquals(FormValidation.Kind.OK, settings.doCheckApiBaseUrlCredentialsId(null, "ollama-url").kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckApiBaseUrlCredentialsId(null, "missing").kind);
        assertEquals(FormValidation.Kind.ERROR, settings.doCheckApiBaseUrlCredentialsId(null, "").kind);
    }
}
