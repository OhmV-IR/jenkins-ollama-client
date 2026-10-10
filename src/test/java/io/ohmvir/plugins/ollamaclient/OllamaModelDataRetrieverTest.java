package io.ohmvir.plugins.ollamaclient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelCapability;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelInputType;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelOutputType;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelThinkingLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class OllamaModelDataRetrieverTest {
    private static ModelData toModelData(String modelName, String showResponse) throws Exception {
        JsonObject json = JsonParser.parseString(showResponse).getAsJsonObject();
        return OllamaModelDataRetriever.toModelData(new OllamaModelSettings(modelName), json);
    }

    @Test
    void completionModelsAcceptTextInput() throws Exception {
        ModelData data = toModelData("llama3", "{\"capabilities\":[\"completion\"]}");

        assertEquals(List.of(ModelInputType.TEXT), data.getInputs());
        assertTrue(data.supportsOutput(ModelOutputType.UNSTRUCTURED_TEXT));
        assertFalse(data.supportsThinking());
        assertFalse(data.hasCapability(ModelCapability.THINKING));
        assertFalse(data.hasCapability(ModelCapability.TOOLS));
        assertEquals("ollama:llama3", data.getModelId());
    }

    @Test
    void multimodalToolAndThinkingCapabilitiesAreMapped() throws Exception {
        ModelData data = toModelData(
                "qwen3-vl", "{\"capabilities\":[\"completion\",\"vision\",\"audio\",\"tools\",\"thinking\"]}");

        assertTrue(data.supportsInput(ModelInputType.TEXT));
        assertTrue(data.supportsInput(ModelInputType.IMAGE));
        assertTrue(data.supportsInput(ModelInputType.AUDIO));
        assertTrue(data.hasCapability(ModelCapability.TOOLS));
        assertTrue(data.hasCapability(ModelCapability.SKILLS));
        assertTrue(data.hasCapability(ModelCapability.THINKING));
        assertEquals(
                List.of(
                        ModelThinkingLevel.OFF,
                        ModelThinkingLevel.ON,
                        ModelThinkingLevel.LOW,
                        ModelThinkingLevel.MEDIUM,
                        ModelThinkingLevel.HIGH,
                        ModelThinkingLevel.MAX),
                data.getSupportedThinkingLevels());
    }

    @Test
    void gptOssUsesNamedThinkingLevelsAndCannotTurnThinkingOff() throws Exception {
        ModelData data = toModelData("gpt-oss:20b", "{\"capabilities\":[\"completion\",\"tools\",\"thinking\"]}");

        assertTrue(data.hasCapability(ModelCapability.THINKING));
        assertFalse(data.supportsThinkingLevel(ModelThinkingLevel.OFF));
        assertFalse(data.supportsThinkingLevel(ModelThinkingLevel.MAX));
        assertTrue(data.supportsThinkingLevel(ModelThinkingLevel.HIGH));
    }

    @Test
    void embeddingOnlyModelsAreNotOfferedForChat() throws Exception {
        ModelData data = toModelData("nomic-embed-text", "{\"capabilities\":[\"embedding\"]}");

        assertTrue(data.getInputs().isEmpty());
        assertTrue(data.getOutputs().isEmpty());
    }

    @Test
    void missingCapabilitiesFallBackToCompletion() throws Exception {
        // Older Ollama versions do not report capabilities
        ModelData data = toModelData("llama2", "{\"modelfile\":\"FROM llama2\"}");

        assertTrue(data.supportsInput(ModelInputType.TEXT));
        assertTrue(data.supportsOutput(ModelOutputType.UNSTRUCTURED_TEXT));
    }

    @Test
    void maxTemperatureMatchesOllama() throws Exception {
        assertEquals(
                2.0d,
                toModelData("llama3", "{\"capabilities\":[\"completion\"]}").getMaxTemperature());
    }
}
