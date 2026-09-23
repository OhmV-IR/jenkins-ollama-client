package io.ohmvir.plugins.ollamaclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hudson.Extension;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.*;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Extension
public class OllamaModelDataRetriever extends ModelDataRetriever<OllamaModelSettings> {
    private static final String RETRIEVE_MODEL_INFO_SUFFIX = "/api/show";
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public OllamaModelDataRetriever() {
        super(OllamaModelSettings.class);
    }

    @Override
    public ModelData retrieveFromConfiguration(OllamaModelSettings configuration)
            throws IOException, InterruptedException {
        ModelData ret = new ModelData(configuration);
        HttpRequest modelDetailsReq = HttpRequest.newBuilder()
                .uri(URI.create(SecretsUtils.getSecretText(configuration.getApiBaseUrlCredentialsId(), null)
                        + RETRIEVE_MODEL_INFO_SUFFIX))
                .POST(HttpRequest.BodyPublishers.ofString(
                        String.format("{\"model\":\"%s\",\"verbose\":true}", configuration.getModelName())))
                .build();
        HttpResponse<String> res = httpClient.send(modelDetailsReq, HttpResponse.BodyHandlers.ofString());
        JsonObject resJson = JsonParser.parseString(res.body()).getAsJsonObject();
        Set<String> capabilities = resJson.getAsJsonArray("capabilities").asList().stream()
                .map(JsonElement::getAsString)
                .collect(Collectors.toSet());
        if (configuration.getModelName().contains("gpt-oss")) {
            ret.setSupportedThinkingLevels(
                    List.of(ModelThinkingLevel.LOW, ModelThinkingLevel.MEDIUM, ModelThinkingLevel.HIGH));
        } else if (capabilities.contains("thinking")) {
            ret.setSupportedThinkingLevels(List.of(
                    ModelThinkingLevel.OFF,
                    ModelThinkingLevel.LOW,
                    ModelThinkingLevel.MEDIUM,
                    ModelThinkingLevel.HIGH,
                    ModelThinkingLevel.MAX));
        }
        ArrayList<ModelCapability> capabilitiesEnumArr = new ArrayList<>();
        capabilitiesEnumArr.add(ModelCapability.STREAMING);
        capabilitiesEnumArr.add(ModelCapability.ADJUSTABLE_SYSTEM_PROMPT);
        capabilitiesEnumArr.add(ModelCapability.CONVERSATIONS);
        capabilitiesEnumArr.add(ModelCapability.OUTPUT_TOKEN_LIMITING);
        capabilitiesEnumArr.add(ModelCapability.CUSTOM_TEMPERATURE);
        capabilitiesEnumArr.add(ModelCapability.CUSTOM_TOP_P);
        capabilitiesEnumArr.add(ModelCapability.CUSTOM_TOP_K);
        capabilitiesEnumArr.add(ModelCapability.CUSTOM_STOP_SEQUENCES);
        capabilitiesEnumArr.add(ModelCapability.PREMATURE_STOP);
        capabilitiesEnumArr.add(ModelCapability.TOKEN_USAGE_METRICS);
        if (capabilities.contains("tools")) {
            capabilitiesEnumArr.add(ModelCapability.TOOLS);
            capabilitiesEnumArr.add(ModelCapability.SKILLS);
        }
        ArrayList<ModelInputType> supportedInputTypes = new ArrayList<>();
        if (capabilities.contains("audio")) {
            supportedInputTypes.add(ModelInputType.AUDIO);
        }
        if (capabilities.contains("vision")) {
            supportedInputTypes.add(ModelInputType.IMAGE);
        }
        ret.setInputs(supportedInputTypes);
        ret.setCapabilities(capabilitiesEnumArr);
        ArrayList<ModelOutputType> outputTypes = new ArrayList<>();
        if (capabilities.contains("completion")) {
            outputTypes.add(ModelOutputType.UNSTRUCTURED_TEXT);
            outputTypes.add(ModelOutputType.STRUCTURED_OUTPUT);
        }
        if (capabilities.contains("embeddings")) {
            outputTypes.add(ModelOutputType.EMBEDDINGS);
        }
        ret.setOutputs(outputTypes);
        ret.setMaxTemperature(2.0d);
        return ret;
    }
}
