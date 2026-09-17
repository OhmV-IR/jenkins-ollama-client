package io.ohmvir.plugins.ollamaclient;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.models.ModelConfiguration;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import jenkins.model.Jenkins;
import lombok.Getter;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;

@Extension
public class OllamaModelSettings extends ModelConfiguration {
    private final @Getter String apiBaseUrlCredentialsId;

    @DataBoundConstructor
    public OllamaModelSettings(String modelName, String apiBaseUrlCredentialsId) throws Descriptor.FormException {
        super(modelName);
        if (SecretsUtils.getSecretText(apiBaseUrlCredentialsId, null) == null) {
            throw new Descriptor.FormException("apiUrlCredentialId does not resolve to a valid string credential", "apiUrlCredentialId");
        }
        this.apiBaseUrlCredentialsId = apiBaseUrlCredentialsId;
    }

    @Override
    public String getProviderType() {
        return "ollama";
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ModelConfiguration> {
        private final static String MODELS_LIST_API_SUFFIX = "/api/tags";
        private static final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public @NonNull String getDisplayName() {
            return "Ollama Model";
        }

        public ListBoxModel doFillModelNameItems(@QueryParameter String apiBaseUrlCredentialId) {
            try {
                HttpRequest modelsListReq = HttpRequest.newBuilder()
                        .uri(URI.create(SecretsUtils.getSecretText(apiBaseUrlCredentialId, null) + MODELS_LIST_API_SUFFIX))
                        .build();
                HttpResponse<String> response = httpClient.send(modelsListReq, HttpResponse.BodyHandlers.ofString());
                JsonObject resJson = JsonParser.parseString(response.body()).getAsJsonObject();
                ListBoxModel models = new ListBoxModel();
                resJson.get("models")
                        .getAsJsonArray()
                        .forEach(model -> models.add(model.getAsJsonObject().get("name").getAsString(),
                                model.getAsJsonObject().get("model").getAsString())
                        );
                return models;
            } catch (Exception e) {
                return new ListBoxModel();
            }
        }

        public ListBoxModel doFillApiBaseUrlCredentialIdItems(
                @AncestorInPath Item context,
                @QueryParameter String apiBaseUrlCredentialId) {

            if (context == null ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER) : !context.hasPermission(Item.CONFIGURE)) {
                return new StandardListBoxModel().includeCurrentValue(apiBaseUrlCredentialId);
            }

            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            context,
                            StandardCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.instanceOf(StringCredentials.class)
                    );
        }

        @POST
        public FormValidation doCheckApiBaseUrlCredentialId(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("API Base URL is required");
            }
            if (SecretsUtils.getSecretText(value, null) == null) {
                return FormValidation.error("API Base URL does not resolve to a string credential");
            }
            return FormValidation.ok();
        }
    }
}
