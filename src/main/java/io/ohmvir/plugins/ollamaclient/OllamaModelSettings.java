package io.ohmvir.plugins.ollamaclient;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Item;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.models.ModelConfiguration;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.verb.POST;

@Extension
public class OllamaModelSettings extends ModelConfiguration {
    public OllamaModelSettings() throws Descriptor.FormException {
        super("", "");
    }

    @DataBoundConstructor
    public OllamaModelSettings(String modelName) throws Descriptor.FormException {
        super(modelName, modelName);
    }

    @Override
    public String getProviderType() {
        return "ollama";
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ModelConfiguration> {
        private static final String MODELS_LIST_API_SUFFIX = "/api/tags";
        private static final Logger LOGGER = Logger.getLogger(OllamaModelSettings.class.getName());
        private static final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        @Override
        public @NonNull String getDisplayName() {
            return "Ollama Model";
        }

        static URI buildModelsListUri(String baseUrl) {
            String normalizedBaseUrl = baseUrl.trim();
            while (normalizedBaseUrl.endsWith("/")) {
                normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
            }
            if (normalizedBaseUrl.endsWith("/api")) {
                normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 4);
            }
            return URI.create(normalizedBaseUrl + MODELS_LIST_API_SUFFIX);
        }

        private static String responseExcerpt(String body) {
            if (body == null || body.isBlank()) {
                return "<empty response body>";
            }
            String excerpt = body.replaceAll("\\s+", " ").trim();
            return excerpt.length() <= 500 ? excerpt : excerpt.substring(0, 500) + "...";
        }

        @POST
        public ListBoxModel doFillModelNameItems(@AncestorInPath Item context) {
            if (context == null
                    ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                    : !context.hasPermission(Item.CONFIGURE)) {
                return new ListBoxModel();
            }

            OllamaClientSettings settings = OllamaClientSettings.get();
            if (settings == null
                    || settings.getApiBaseUrlCredentialsId() == null
                    || settings.getApiBaseUrlCredentialsId().isBlank()) {
                LOGGER.warning("Cannot fetch Ollama models: API base URL credential is not configured");
                return new ListBoxModel();
            }

            try {
                String configuredBaseUrl = SecretsUtils.getSecretText(settings.getApiBaseUrlCredentialsId(), context);
                if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
                    LOGGER.warning("Cannot fetch Ollama models: configured API base URL credential was not resolved");
                    return new ListBoxModel();
                }

                URI endpoint = buildModelsListUri(configuredBaseUrl);
                HttpRequest.Builder modelsListReqBuilder = HttpRequest.newBuilder()
                        .uri(endpoint)
                        .header("User-Agent", "Jenkins-Ollama-Plugin/1.0")
                        .GET();

                if (settings.isUsesCloudflareAccess()) {
                    String clientId =
                            SecretsUtils.getSecretText(settings.getCloudflareAccessClientIdCredentialId(), context);
                    String clientSecret =
                            SecretsUtils.getSecretText(settings.getCloudflareAccessClientSecretCredentialId(), context);

                    if (clientId != null && clientSecret != null) {
                        modelsListReqBuilder.header("CF-Access-Client-Id", clientId);
                        modelsListReqBuilder.header("CF-Access-Client-Secret", clientSecret);
                    }
                }

                HttpRequest modelsListReq = modelsListReqBuilder.build();
                HttpResponse<String> response = httpClient.send(modelsListReq, HttpResponse.BodyHandlers.ofString());

                String endpointDescription = endpoint.getHost() + endpoint.getPath();
                if (response.statusCode() != 200) {
                    LOGGER.log(Level.WARNING, "Ollama model-list request to {0} returned HTTP {1}: {2}", new Object[] {
                        endpointDescription, response.statusCode(), responseExcerpt(response.body())
                    });
                    return new ListBoxModel();
                }
                if (response.body() == null || response.body().isBlank()) {
                    LOGGER.log(
                            Level.WARNING,
                            "Ollama model-list request to {0} returned an empty response body",
                            endpointDescription);
                    return new ListBoxModel();
                }

                JsonElement jsonElement;
                try {
                    jsonElement = JsonParser.parseString(response.body());
                } catch (JsonParseException e) {
                    LOGGER.log(
                            Level.WARNING,
                            "Ollama model-list response from {0} was not valid JSON: {1}",
                            new Object[] {endpointDescription, responseExcerpt(response.body())});
                    return new ListBoxModel();
                }
                if (!jsonElement.isJsonObject()) {
                    LOGGER.log(
                            Level.WARNING,
                            "Ollama model-list response from {0} was not a JSON object: {1}",
                            new Object[] {endpointDescription, responseExcerpt(response.body())});
                    return new ListBoxModel();
                }

                JsonObject resJson = jsonElement.getAsJsonObject();
                if (resJson.has("error")) {
                    LOGGER.log(
                            Level.WARNING, "Ollama model-list endpoint {0} returned an API error: {1}", new Object[] {
                                endpointDescription,
                                responseExcerpt(resJson.get("error").toString())
                            });
                    return new ListBoxModel();
                }
                if (!resJson.has("models") || !resJson.get("models").isJsonArray()) {
                    LOGGER.log(
                            Level.WARNING,
                            "Ollama model-list response from {0} has no models array: {1}",
                            new Object[] {endpointDescription, responseExcerpt(response.body())});
                    return new ListBoxModel();
                }

                ListBoxModel models = new ListBoxModel();
                resJson.getAsJsonArray("models").forEach(element -> {
                    if (element.isJsonObject()) {
                        JsonObject modelObj = element.getAsJsonObject();
                        String name =
                                modelObj.has("name") ? modelObj.get("name").getAsString() : "";
                        String model =
                                modelObj.has("model") ? modelObj.get("model").getAsString() : name;
                        if (!name.isEmpty()) {
                            models.add(name, model);
                        }
                    }
                });

                if (models.isEmpty()) {
                    LOGGER.log(
                            Level.INFO, "Ollama model-list endpoint {0} returned no named models", endpointDescription);
                }
                return models;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Interrupted while fetching the Ollama model list", e);
                return new ListBoxModel();
            } catch (IOException | IllegalStateException e) {
                LOGGER.log(Level.WARNING, "Failed to fetch or parse the Ollama model list", e);
                return new ListBoxModel();
            }
        }
    }
}
