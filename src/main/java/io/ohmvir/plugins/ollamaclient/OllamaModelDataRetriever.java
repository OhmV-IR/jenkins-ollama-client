package io.ohmvir.plugins.ollamaclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import hudson.Extension;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.*;
import java.io.IOException;
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
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public OllamaModelDataRetriever() {
        super(OllamaModelSettings.class);
    }

    /**
     * gpt-oss uses named reasoning efforts and cannot have thinking turned off, other thinking models take a boolean.
     */
    static boolean usesNamedThinkingLevels(String modelName) {
        return modelName != null && modelName.contains("gpt-oss");
    }

    @Override
    public ModelData retrieveFromConfiguration(OllamaModelSettings configuration)
            throws IOException, InterruptedException {
        OllamaClientSettings settings = OllamaClientSettings.get();
        String baseUrl = settings == null ? null : settings.resolveApiBaseUrl(null);
        if (baseUrl == null) {
            throw new IOException("Ollama API base URL credential is not configured or could not be resolved");
        }

        JsonObject reqBody = new JsonObject();
        reqBody.addProperty("model", configuration.getModelName());
        HttpRequest.Builder modelDetailsReqBuilder = HttpRequest.newBuilder()
                .uri(OllamaClientSettings.buildApiUri(baseUrl, RETRIEVE_MODEL_INFO_SUFFIX))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(reqBody.toString()));
        settings.applyCloudflareAccessHeaders(modelDetailsReqBuilder, null);

        HttpResponse<String> res =
                httpClient.send(modelDetailsReqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Ollama /api/show for model " + configuration.getModelName() + " returned HTTP "
                    + res.statusCode() + ": " + res.body());
        }
        JsonObject resJson;
        try {
            resJson = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("Ollama /api/show returned an invalid response: " + res.body(), e);
        }
        return toModelData(configuration, resJson);
    }

    static ModelData toModelData(OllamaModelSettings configuration, JsonObject showResponse) {
        ModelData ret = new ModelData(configuration);
        Set<String> capabilities;
        if (showResponse.has("capabilities") && showResponse.get("capabilities").isJsonArray()) {
            capabilities = showResponse.getAsJsonArray("capabilities").asList().stream()
                    .map(JsonElement::getAsString)
                    .collect(Collectors.toSet());
        } else {
            // Ollama versions before capabilities were reported only served completion models
            capabilities = Set.of("completion");
        }

        if (usesNamedThinkingLevels(configuration.getModelName())) {
            ret.setSupportedThinkingLevels(List.of(
                    ModelThinkingLevel.ON, ModelThinkingLevel.LOW, ModelThinkingLevel.MEDIUM, ModelThinkingLevel.HIGH));
        } else if (capabilities.contains("thinking")) {
            ret.setSupportedThinkingLevels(List.of(
                    ModelThinkingLevel.OFF,
                    ModelThinkingLevel.ON,
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
        if (ret.supportsThinking()) {
            capabilitiesEnumArr.add(ModelCapability.THINKING);
        }
        if (capabilities.contains("tools")) {
            capabilitiesEnumArr.add(ModelCapability.TOOLS);
            capabilitiesEnumArr.add(ModelCapability.SKILLS);
        }
        ArrayList<ModelInputType> supportedInputTypes = new ArrayList<>();
        if (capabilities.contains("completion")) {
            supportedInputTypes.add(ModelInputType.TEXT);
        }
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
        // Embedding models ("embedding" capability) are not advertised, this client only implements /api/chat
        ret.setOutputs(outputTypes);
        ret.setMaxTemperature(2.0d);
        return ret;
    }
}
