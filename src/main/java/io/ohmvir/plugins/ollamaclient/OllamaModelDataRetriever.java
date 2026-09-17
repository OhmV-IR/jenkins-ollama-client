package io.ohmvir.plugins.ollamaclient;

import hudson.Extension;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelData;
import io.ohmvir.plugins.jenkinsaisynapse.api.models.ModelDataRetriever;
import java.io.IOException;

@Extension
public class OllamaModelDataRetriever extends ModelDataRetriever<OllamaModelSettings> {
    public OllamaModelDataRetriever() {
        super(OllamaModelSettings.class);
    }

    @Override
    public ModelData retrieveFromConfiguration(OllamaModelSettings configuration)
            throws IOException, InterruptedException {
        return null;
    }
}
