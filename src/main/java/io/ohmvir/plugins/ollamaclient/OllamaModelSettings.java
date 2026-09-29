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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import jenkins.model.Jenkins;
import lombok.Getter;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

@Extension
public class OllamaModelSettings extends ModelConfiguration {
    private final @Getter String apiBaseUrlCredentialsId;

    public OllamaModelSettings() throws Descriptor.FormException {
        super("", "");
        apiBaseUrlCredentialsId = null;
    }

    @DataBoundConstructor
    public OllamaModelSettings(String modelName, String apiBaseUrlCredentialsId) throws Descriptor.FormException {
        super(modelName, modelName);
        if (SecretsUtils.getSecretText(apiBaseUrlCredentialsId, null) == null) {
            throw new Descriptor.FormException(
                    "apiUrlCredentialId does not resolve to a valid string credential", "apiUrlCredentialId");
        }
        this.apiBaseUrlCredentialsId = apiBaseUrlCredentialsId;
    }

    @Override
    public String getProviderType() {
        return "ollama";
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ModelConfiguration> {
        private static final String MODELS_LIST_API_SUFFIX = "/api/tags";
        private static final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public @NonNull String getDisplayName() {
            return "Ollama Model";
        }
        @POST
        public ListBoxModel doFillModelNameItems(@AncestorInPath Item context, @QueryParameter String apiBaseUrlCredentialId) {
            // 1. Permission check (required for @POST handlers)
            if (context == null ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER) : !context.hasPermission(Item.CONFIGURE)) {
                return new ListBoxModel();
            }

            if (apiBaseUrlCredentialId == null || apiBaseUrlCredentialId.trim().isEmpty()) {
                return new ListBoxModel();
            }

            try {
                String baseUrl = SecretsUtils.getSecretText(apiBaseUrlCredentialId, context);
                if (baseUrl == null || baseUrl.isBlank()) {
                    return new ListBoxModel();
                }

                // Standardize URL concatenation to prevent invalid URIs
                if (!baseUrl.endsWith("/")) {
                    baseUrl += "/";
                }
                String suffix = MODELS_LIST_API_SUFFIX.startsWith("/") ? MODELS_LIST_API_SUFFIX.substring(1) : MODELS_LIST_API_SUFFIX;

                HttpRequest.Builder modelsListReqBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + suffix))
                        .GET();

                // 2. Safely resolve Cloudflare Access Settings
                OllamaClientSettings settings = OllamaClientSettings.get();
                if (settings != null && settings.isUsesCloudflareAccess()) {
                    String clientId = SecretsUtils.getSecretText(settings.getCloudflareAccessClientIdCredentialId(), context);
                    String clientSecret = SecretsUtils.getSecretText(settings.getCloudflareAccessClientSecretCredentialId(), context);

                    if (clientId != null && clientSecret != null) {
                        modelsListReqBuilder.header("CF-Access-Client-Id", clientId);
                        modelsListReqBuilder.header("CF-Access-Client-Secret", clientSecret);
                    }
                }

                HttpRequest modelsListReq = modelsListReqBuilder.build();
                HttpResponse<String> response = httpClient.send(modelsListReq, HttpResponse.BodyHandlers.ofString());

                // 3. Handle non-200 HTTP responses safely
                if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
                    return new ListBoxModel();
                }

                JsonObject resJson = JsonParser.parseString(response.body()).getAsJsonObject();
                if (resJson.has("error") || !resJson.has("models") || !resJson.get("models").isJsonArray()) {
                    return new ListBoxModel();
                }

                ListBoxModel models = new ListBoxModel();
                resJson.getAsJsonArray("models").forEach(element -> {
                    if (element.isJsonObject()) {
                        JsonObject modelObj = element.getAsJsonObject();
                        String name = modelObj.has("name") ? modelObj.get("name").getAsString() : "";
                        String model = modelObj.has("model") ? modelObj.get("model").getAsString() : name;
                        if (!name.isEmpty()) {
                            models.add(name, model);
                        }
                    }
                });

                return models;
            } catch (Exception e) {
                // Prevent Jenkins UI crashes during dynamic field population
                return new ListBoxModel();
            }
        }

        @POST
        public ListBoxModel doFillApiBaseUrlCredentialIdItems(
                @AncestorInPath Item context, @QueryParameter String apiBaseUrlCredentialId) {

            if (context == null
                    ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    : !context.hasPermission(Item.CONFIGURE)) {
                return new StandardListBoxModel().includeCurrentValue(apiBaseUrlCredentialId);
            }

            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            context,
                            StandardCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.instanceOf(StringCredentials.class));
        }

        @POST
        public FormValidation doCheckApiBaseUrlCredentialId(
                @AncestorInPath Item context, @QueryParameter String value) {
            // 4. Added permission check and passed context to credential lookup
            if (context == null ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER) : !context.hasPermission(Item.CONFIGURE)) {
                return FormValidation.ok();
            }

            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("API Base URL is required");
            }

            if (SecretsUtils.getSecretText(value, context) == null) {
                return FormValidation.error("API Base URL does not resolve to a string credential");
            }

            return FormValidation.ok();
        }
    }
}
