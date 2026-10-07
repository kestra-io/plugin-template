package io.kestra.plugin.mailchimp.reports;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Set;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.AbstractMailchimpTask;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.models.FetchOutput;

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
    title = "List the email activity of a Mailchimp campaign",
    description = """
        Reads the per-recipient activity of a sent campaign with `GET /reports/{campaignId}/email-activity` (the `emails` array), 1000 per request, until `maxItems` or the end.

        Each row exposes `emailAddress`, `emailId` and `activity` (a list of `{action, timestamp, url, ip}`, left as Mailchimp sends it); every other field keeps its original key."""
)
@Plugin(
    examples = {
        @Example(
            title = "Store the activity of a campaign as an ION file.",
            full = true,
            code = """
                id: mailchimp_email_activity
                namespace: company.team

                tasks:
                  - id: activity
                    type: io.kestra.plugin.mailchimp.reports.ListEmailActivity
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    campaignId: "42694e9e57"
                    since: "2026-01-01T00:00:00Z"
                    fetchType: STORE
                """
        )
    }
)
public class ListEmailActivity extends AbstractMailchimpTask implements RunnableTask<FetchOutput> {
    private static final Set<String> CAMEL = Set.of("email_address", "email_id");

    @Schema(title = "Campaign ID", description = "Id of a sent campaign, as returned by `ListCampaigns`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> campaignId;

    @Schema(title = "Since", description = "Only activity after this ISO-8601 date and time (`since`).")
    @PluginProperty(group = "processing")
    private Property<Instant> since;

    @Schema(
        title = "Filter bots",
        description = "Excludes bot activity via the API's `filter_bots` parameter (sent only when `true`). Default `false`."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Boolean> filterBots = Property.ofValue(false);

    @Schema(
        title = "Fetch strategy",
        description = "`FETCH_ONE`: first recipient only.\n`FETCH` (default): all recipients in memory.\n`STORE`: write rows as ION to internal storage and return the URI.\n`NONE`: only count them."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Max items", description = "Stop after this many recipients (default: unlimited). Must be at least 1.")
    @PluginProperty(group = "advanced")
    private Property<Integer> maxItems;

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var rCampaignId = runContext.render(campaignId).as(String.class).orElseThrow(() -> new IllegalArgumentException("'campaignId' is required"));
        var path = "/reports/" + MailchimpClient.segment(rCampaignId) + "/email-activity";

        var query = new LinkedHashMap<String, String>();
        FetchOutput.put(query, "since", runContext.render(since).as(Instant.class));
        FetchOutput.put(query, "filter_bots", runContext.render(filterBots).as(Boolean.class).filter(Boolean::booleanValue));

        try (var client = client(runContext)) {
            return FetchOutput.fetchPaged(
                runContext, client, path, query, "emails",
                runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH),
                runContext.render(maxItems).as(Integer.class).orElse(null),
                item -> FetchOutput.row(item, CAMEL)
            );
        }
    }
}
