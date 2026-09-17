package io.ohmvir.plugins.ollamaclient;

import io.ohmvir.plugins.jenkinsaisynapse.api.client.ModelClient;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.ModelInput;
import io.ohmvir.plugins.jenkinsaisynapse.api.input.ModelRequest;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.output.ModelOutput;
import java.util.List;

public class OllamaClient extends ModelClient<OllamaModelSettings, OllamaClientSettings> {
    public OllamaClient(
            ModelData modelData, OllamaModelSettings configuration, OllamaClientSettings clientConfiguration) {
        super(modelData, configuration, clientConfiguration);
    }

    @Override
    public List<ModelOutput> takeStep(ModelRequest request, List<ModelInput> turnInputs) {
        return List.of();
    }
}
