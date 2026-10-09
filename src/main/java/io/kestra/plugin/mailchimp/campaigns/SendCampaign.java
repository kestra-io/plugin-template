package io.kestra.plugin.mailchimp.campaigns;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.AbstractMailchimpTask;
import io.kestra.plugin.mailchimp.MailchimpClient;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Send a Mailchimp campaign",
    description = """
        Sending a campaign is irreversible: once Mailchimp accepts it, real email goes to the whole audience and cannot be recalled. Only run this on campaigns you have reviewed.

        By default (`requireReady: true`) the task first reads `GET /campaigns/{campaignId}/send-checklist` and fails, without sending, when `is_ready` is not true; the failing checklist items are listed in the error. It then calls `POST /campaigns/{campaignId}/actions/send` (Mailchimp answers 204 without a body).

        The send call is never retried after a timeout or a 5xx, so a duplicate send cannot happen (only HTTP 429 is retried). If it fails that way, check the campaign status in Mailchimp before re-running the flow.

        Only email contacts who have opted in; you are responsible for GDPR and Mailchimp terms."""
)
@Plugin(
    examples = {
        @Example(
            title = "WARNING, this really sends the campaign: send it once its checklist is ready.",
            full = true,
            code = """
                # WARNING: this flow really sends the campaign to its whole audience and cannot be undone.
                id: mailchimp_send_campaign
                namespace: company.team

                tasks:
                  - id: send
                    type: io.kestra.plugin.mailchimp.campaigns.SendCampaign
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    campaignId: "a1b2c3d4e5"
                    requireReady: true
                """
        )
    }
)
public class SendCampaign extends AbstractMailchimpTask implements RunnableTask<SendCampaign.Output> {
    private static final int MAX_ITEMS_IN_MESSAGE = 10;

    @Schema(title = "Campaign ID", description = "Mailchimp campaign id, found with `ListCampaigns`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> campaignId;

    @Builder.Default
    @Schema(
        title = "Require a ready checklist",
        description = "When true (default), the send checklist must report `is_ready` before sending. When false the checklist is not called and `checklistReady` is null in the output."
    )
    @PluginProperty(group = "advanced")
    private Property<Boolean> requireReady = Property.ofValue(true);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var id = runContext.render(campaignId).as(String.class).orElseThrow(() -> new IllegalArgumentException("'campaignId' is required"));
        var segment = MailchimpClient.segment(id);
        var base = "/campaigns/" + segment;
        var check = runContext.render(requireReady).as(Boolean.class).orElse(true);

        try (var client = client(runContext)) {
            Boolean ready = null;
            if (check) {
                var checklist = client.send("GET", base + "/send-checklist", null, null);
                ready = checklist.path("is_ready").isBoolean() && checklist.path("is_ready").booleanValue();
                if (!ready) {
                    throw new IllegalStateException("Campaign '" + id + "' is not ready to send, nothing was sent. " + describe(checklist.path("items")));
                }
            }
            client.send("POST", base + "/actions/send", null, null);
            return Output.builder().campaignId(id).checklistReady(ready).sent(true).build();
        }
    }

    private static String describe(JsonNode items) {
        var sb = new StringBuilder("Checklist items: ");
        var n = 0;
        for (var item : items) {
            var type = item.path("type").asText("");
            if ("success".equals(type)) {
                continue;
            }
            if (n++ == MAX_ITEMS_IN_MESSAGE) {
                sb.append("; ...");
                break;
            }
            sb.append(n > 1 ? "; " : "").append('[').append(type).append("] ").append(cut(item.path("details").asText(item.path("heading").asText(""))));
        }
        return n == 0 ? "The checklist response was unreadable or not ready (no is_ready=true, no failing item listed)." : sb.toString();
    }

    private static String cut(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Campaign ID")
        private final String campaignId;

        @Schema(title = "Checklist ready", description = "Whether the send checklist was ready. Null when `requireReady` is false because the checklist was not called.")
        private final Boolean checklistReady;

        @Schema(title = "Sent", description = "True once Mailchimp accepted the send request.")
        private final Boolean sent;
    }
}
