package io.ohmvir.plugins.ollamaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.net.URI;
import org.junit.jupiter.api.Test;

class OllamaModelSettingsTest {
    @Test
    void buildsModelListUriFromServerRoot() {
        URI uri = OllamaModelSettings.DescriptorImpl.buildModelsListUri("http://localhost:11434/");

        assertEquals(URI.create("http://localhost:11434/api/tags"), uri);
    }

    @Test
    void buildsModelListUriFromApiBase() {
        URI uri = OllamaModelSettings.DescriptorImpl.buildModelsListUri("http://localhost:11434/api/");

        assertEquals(URI.create("http://localhost:11434/api/tags"), uri);
    }

    @Test
    void modelNameIsValidated() {
        OllamaModelSettings.DescriptorImpl descriptor = new OllamaModelSettings.DescriptorImpl();

        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckModelName("").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckModelName("llama3:latest").kind);
    }

    @Test
    void modelNameIsUsedForIdAndDisplayName() throws Exception {
        OllamaModelSettings settings = new OllamaModelSettings("llama3:latest");

        assertEquals("ollama:llama3:latest", settings.getModelId());
        assertEquals("ollama:llama3:latest", settings.getModelIdDisplayName());
        assertThrows(Descriptor.FormException.class, () -> new OllamaModelSettings(""));
    }
}
