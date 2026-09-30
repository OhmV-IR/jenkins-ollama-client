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
import java.util.Collections;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import lombok.Getter;
import lombok.Setter;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jspecify.annotations.NonNull;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

@Extension
public class OllamaClientSettings extends ModelClientConfiguration {

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
        if (usesCloudflareAccess && cloudflareAccessClientIdCredentialId.isBlank()) {
            throw new FormException(
                    "Cloudflare access credentials must be specified", "cloudflareAccessClientIdCredentialId");
        }
        this.cloudflareAccessClientIdCredentialId = cloudflareAccessClientIdCredentialId;
        if (usesCloudflareAccess && cloudflareAccessClientSecretCredentialId.isBlank()) {
            throw new FormException(
                    "Cloudflare access credentials must be specified", "cloudflareAccessClientSecretCredentialId");
        }
        this.cloudflareAccessClientSecretCredentialId = cloudflareAccessClientSecretCredentialId;
        if (apiBaseUrlCredentialsId.trim().isEmpty()) {
            throw new FormException("Ollama base api url should not be empty", "apiBaseUrlCredentialsId");
        }
        this.apiBaseUrlCredentialsId = apiBaseUrlCredentialsId;
    }

    @Override
    public @NonNull String getDisplayName() {
        return "Ollama Client Settings";
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
    public FormValidation doCheckApiBaseUrlCredentialId(@AncestorInPath Item context, @QueryParameter String value) {
        // 4. Added permission check and passed context to credential lookup
        if (context == null
                ? !Jenkins.get().hasPermission(Jenkins.ADMINISTER)
                : !context.hasPermission(Item.CONFIGURE)) {
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
        if (value.isEmpty()) {
            return FormValidation.error(
                    "Cloudflare Access Client Secret must be filled in if using cloudflare access",
                    "cloudflareAccessClientSecretCredentialId");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckCloudflareAccessClientIdCredentialId(
            @QueryParameter String value, @QueryParameter boolean usesCloudflareAccess) {
        if (!usesCloudflareAccess) {
            return FormValidation.ok();
        }
        if (value.isEmpty()) {
            return FormValidation.error(
                    "Cloudflare Access Client ID must be filled in if using cloudflare access",
                    "cloudflareAccessClientIdCredentialId");
        }
        return FormValidation.ok();
    }

    public static OllamaClientSettings get() {
        return GlobalConfiguration.all().get(OllamaClientSettings.class);
    }
}
