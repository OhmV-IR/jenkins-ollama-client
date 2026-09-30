package io.ohmvir.plugins.ollamaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
