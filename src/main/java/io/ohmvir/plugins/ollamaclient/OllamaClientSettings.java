package io.ohmvir.plugins.ollamaclient;

import hudson.Extension;
import hudson.util.FormValidation;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.client.ModelClientConfiguration;
import jenkins.model.Jenkins;
import lombok.Getter;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.interceptor.RequirePOST;

@Extension
public class OllamaClientSettings extends ModelClientConfiguration {
    private @Getter final long keepAliveSeconds;

    public OllamaClientSettings() throws FormException {
        super(0L);
        keepAliveSeconds = 0L;
    }

    @DataBoundConstructor
    public OllamaClientSettings(long keepAliveSeconds, long timeoutSeconds) throws FormException {
        super(timeoutSeconds);
        if(keepAliveSeconds < 0) {
            throw new FormException("Keep alive seconds must be greater than or equal to zero", "keepAliveSeconds");
        }
        this.keepAliveSeconds = keepAliveSeconds;
    }

    @Override
    public @NonNull String getDisplayName() {
        return "Ollama Client Settings";
    }
}