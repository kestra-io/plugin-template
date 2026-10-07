package io.kestra.plugin.mailchimp;

import java.time.Duration;

import com.google.common.annotations.VisibleForTesting;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractMailchimpTask extends Task implements MailchimpConnectionInterface {

    @ToString.Exclude
    @Schema(
        title = "Mailchimp API key",
        description = "Set exactly one of `apiKey` or `accessToken`. The data center is taken from the key suffix (for example `-us19`) unless `server` is set."
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> apiKey;

    @ToString.Exclude
    @Schema(
        title = "OAuth access token",
        description = "Set exactly one of `apiKey` or `accessToken`. `server` is required with an access token."
    )
    @PluginProperty(group = "connection", secret = true)
    private Property<String> accessToken;

    @Schema(
        title = "Data center",
        description = "Mailchimp data center such as `us19`. Required with `accessToken`, optional with `apiKey` (overrides the key suffix)."
    )
    @PluginProperty(group = "connection")
    private Property<String> server;

    @Schema(
        title = "Base URL override",
        description = "Replaces `https://<server>.api.mailchimp.com/3.0`. For testing and proxies only; must be https, or http on localhost / 127.0.0.1."
    )
    @PluginProperty(group = "advanced")
    private Property<String> baseUrl;

    @Builder.Default
    @Schema(
        title = "Max retries",
        description = "Retries on HTTP 429 (all methods) and on 5xx or I/O errors (GET and PUT only), with exponential backoff. `0` disables retries."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> maxRetries = Property.ofValue(3);

    @Builder.Default
    @Schema(title = "Request timeout", description = "Per-call timeout. Mailchimp itself cuts requests off after 120 seconds.")
    @PluginProperty(group = "advanced")
    private Property<Duration> timeout = Property.ofValue(Duration.ofSeconds(120));

    @VisibleForTesting
    protected MailchimpClient client(RunContext runContext) throws Exception {
        return MailchimpConnectionInterface.client(runContext, this);
    }
}
