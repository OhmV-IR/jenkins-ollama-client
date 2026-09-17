package io.ohmvir.plugins.ollamaclient;

import hudson.Extension;
import io.ohmvir.plugins.jenkinsaisynapse.api.client.ModelClient;
import io.ohmvir.plugins.jenkinsaisynapse.api.client.ModelClientFactory;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;

@Extension
public class OllamaClientFactory extends ModelClientFactory<OllamaModelSettings, OllamaClientSettings> {
    @Override
    public ModelClient<OllamaModelSettings, OllamaClientSettings> createClient(
            ModelData modelData, OllamaModelSettings modelConfiguration, OllamaClientSettings clientConfiguration) {
        return new OllamaClient(modelData, modelConfiguration, clientConfiguration);
    }
}
