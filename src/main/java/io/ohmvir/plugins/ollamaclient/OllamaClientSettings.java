package io.ohmvir.plugins.ollamaclient;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.ohmvir.plugins.jenkinsaisynapse.configuration.client.ModelClientConfiguration;
import io.ohmvir.plugins.jenkinsaisynapse.utils.SecretsUtils;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Collections;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.Setter;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

@Extension
public class OllamaClientSettings extends ModelClientConfiguration {
    private static final Logger LOGGER = Logger.getLogger(OllamaClientSettings.class.getName());

    private @Getter @Setter(onMethod_ = @DataBoundSetter) long keepAliveSeconds;
    private @Getter @Setter(onMethod_ = @DataBoundSetter) boolean usesCloudflareAccess;
    private @Getter @Setter(onMethod_ = @DataBoundSetter) String apiBaseUrlCredentialsId;
    private @Getter @Setter(onMethod_ = @DataBoundSetter) String cloudflareAccessClientIdCredentialId;
    private @Getter @Setter(onMethod_ = @DataBoundSetter) String cloudflareAccessClientSecretCredentialId;

    public OllamaClientSettings() throws FormException {
        super(0L);
        keepAliveSeconds = 0L;
        usesCloudflareAccess = false;
        cloudflareAccessClientIdCredentialId = null;
        cloudflareAccessClientSecretCredentialId = null;
        apiBaseUrlCredentialsId = null;
        // GlobalConfiguration does not load persisted values on its own
        load();
    }

    @DataBoundConstructor
    public OllamaClientSettings(
            long keepAliveSeconds,
            long timeoutSeconds,
            String apiBaseUrlCredentialsId,
            boolean usesCloudflareAccess,
            String cloudflareAccessClientIdCredentialId,
            String cloudflareAccessClientSecretCredentialId)
            throws FormException {
        super(timeoutSeconds);
        if (keepAliveSeconds < 0) {
            throw new FormException("Keep alive seconds must be greater than or equal to zero", "keepAliveSeconds");
        }
        this.keepAliveSeconds = keepAliveSeconds;
        this.usesCloudflareAccess = usesCloudflareAccess;
        if (usesCloudflareAccess && isBlank(cloudflareAccessClientIdCredentialId)) {
            throw new FormException(
                    "Cloudflare access credentials must be specified", "cloudflareAccessClientIdCredentialId");
        }
        this.cloudflareAccessClientIdCredentialId = cloudflareAccessClientIdCredentialId;
        if (usesCloudflareAccess && isBlank(cloudflareAccessClientSecretCredentialId)) {
            throw new FormException(
                    "Cloudflare access credentials must be specified", "cloudflareAccessClientSecretCredentialId");
        }
        this.cloudflareAccessClientSecretCredentialId = cloudflareAccessClientSecretCredentialId;
        if (isBlank(apiBaseUrlCredentialsId)) {
            throw new FormException("Ollama base api url should not be empty", "apiBaseUrlCredentialsId");
        }
        this.apiBaseUrlCredentialsId = apiBaseUrlCredentialsId;
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        req.bindJSON(this, json);
        if (keepAliveSeconds < 0) {
            throw new FormException("Keep alive seconds must be greater than or equal to zero", "keepAliveSeconds");
        }
        // GlobalConfiguration#configure only binds, it does not persist
        save();
        return true;
    }

    @Override
    public @NonNull String getDisplayName() {
        return "Ollama Client Settings";
    }

    /**
     * Builds the URI for an Ollama API endpoint, tolerating base URLs with trailing slashes or an {@code /api} suffix.
     * @param baseUrl The configured base URL, eg {@code http://localhost:11434}
     * @param endpoint The endpoint path, eg {@code /api/chat}
     */
    static URI buildApiUri(String baseUrl, String endpoint) {
        String normalizedBaseUrl = baseUrl.trim();
        while (normalizedBaseUrl.endsWith("/")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
        }
        if (normalizedBaseUrl.endsWith("/api")) {
            normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 4);
        }
        return URI.create(normalizedBaseUrl + endpoint);
    }

    /**
     * @return The configured API base URL, or null if the credential is not configured or cannot be resolved.
     */
    @Nullable
    String resolveApiBaseUrl(@Nullable Item context) {
        String baseUrl = SecretsUtils.getSecretText(apiBaseUrlCredentialsId, context);
        return isBlank(baseUrl) ? null : baseUrl;
    }

    /**
     * Adds the Cloudflare Access service token headers to the request if Cloudflare Access is enabled.
     */
    void applyCloudflareAccessHeaders(HttpRequest.Builder builder, @Nullable Item context) {
        if (!usesCloudflareAccess) {
            return;
        }
        String clientId = SecretsUtils.getSecretText(cloudflareAccessClientIdCredentialId, context);
        String clientSecret = SecretsUtils.getSecretText(cloudflareAccessClientSecretCredentialId, context);
        if (clientId == null || clientSecret == null) {
            LOGGER.warning("Cloudflare Access is enabled but its credentials could not be resolved, "
                    + "sending the request without Cloudflare Access headers");
            return;
        }
        builder.header("CF-Access-Client-Id", clientId);
        builder.header("CF-Access-Client-Secret", clientSecret);
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    @POST
    public ListBoxModel doFillCloudflareAccessClientIdCredentialIdItems(
            @AncestorInPath Item context, @QueryParameter String cloudflareAccessClientIdCredentialId) {

        if (context == null
                ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                : !context.hasPermission(Item.CONFIGURE)) {
            return new StandardListBoxModel().includeCurrentValue(cloudflareAccessClientIdCredentialId);
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
    public ListBoxModel doFillApiBaseUrlCredentialsIdItems(
            @AncestorInPath Item context, @QueryParameter String apiBaseUrlCredentialsId) {

        if (context == null
                ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                : !context.hasPermission(Item.CONFIGURE)) {
            return new StandardListBoxModel().includeCurrentValue(apiBaseUrlCredentialsId);
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
    public FormValidation doCheckApiBaseUrlCredentialsId(@AncestorInPath Item context, @QueryParameter String value) {
        if (context == null
                ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                : !context.hasPermission(Item.CONFIGURE)) {
            return FormValidation.ok();
        }

        if (isBlank(value)) {
            return FormValidation.error("API Base URL is required");
        }

        if (SecretsUtils.getSecretText(value, context) == null) {
            return FormValidation.error("API Base URL does not resolve to a string credential");
        }

        return FormValidation.ok();
    }

    @POST
    public ListBoxModel doFillCloudflareAccessClientSecretCredentialIdItems(
            @AncestorInPath Item context, @QueryParameter String cloudflareAccessClientSecretCredentialId) {

        if (context == null
                ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                : !context.hasPermission(Item.CONFIGURE)) {
            return new StandardListBoxModel().includeCurrentValue(cloudflareAccessClientSecretCredentialId);
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
    public FormValidation doCheckCloudflareAccessClientSecretCredentialId(
            @QueryParameter String value, @QueryParameter boolean usesCloudflareAccess) {
        if (!usesCloudflareAccess) {
            return FormValidation.ok();
        }
        if (isBlank(value)) {
            return FormValidation.error("Cloudflare Access Client Secret must be filled in if using cloudflare access");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckCloudflareAccessClientIdCredentialId(
            @QueryParameter String value, @QueryParameter boolean usesCloudflareAccess) {
        if (!usesCloudflareAccess) {
            return FormValidation.ok();
        }
        if (isBlank(value)) {
            return FormValidation.error("Cloudflare Access Client ID must be filled in if using cloudflare access");
        }
        return FormValidation.ok();
    }

    public static OllamaClientSettings get() {
        return GlobalConfiguration.all().get(OllamaClientSettings.class);
    }
}
