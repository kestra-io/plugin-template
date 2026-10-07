package io.kestra.plugin.mailchimp.campaigns;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.AbstractMailchimpTask;
import io.kestra.plugin.mailchimp.models.FetchOutput;
import io.kestra.plugin.mailchimp.models.SortDir;

import io.swagger.v3.oas.annotations.media.Schema;
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
    title = "List Mailchimp campaigns",
    description = """
        Reads campaigns with `GET /campaigns`, 1000 per request, until `maxItems` or the end.

        Each row exposes `id`, `type`, `status`, `sendTime`, `emailsSent`, plus `listId` (from `recipients`), `subjectLine` and `title` (from `settings`); every other Mailchimp field keeps its original key."""
)
@Plugin(
    examples = {
        @Example(
            title = "Fetch the campaigns sent since the start of the year, newest first.",
            full = true,
            code = """
                id: mailchimp_list_campaigns
                namespace: company.team

                tasks:
                  - id: campaigns
                    type: io.kestra.plugin.mailchimp.campaigns.ListCampaigns
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    status: SENT
                    sinceSendTime: "2026-01-01T00:00:00Z"
                    sortField: SEND_TIME
                    sortDir: DESC
                    fetchType: FETCH
                """
        )
    }
)
public class ListCampaigns extends AbstractMailchimpTask implements RunnableTask<FetchOutput> {
    private static final Set<String> CAMEL = Set.of("send_time", "emails_sent");

    public enum Type { REGULAR, PLAINTEXT, ABSPLIT, RSS, VARIATE }

    public enum Status { SAVE, PAUSED, SCHEDULE, SENDING, SENT }

    public enum SortField { CREATE_TIME, SEND_TIME }

    @Schema(
        title = "Campaign type",
        description = "Only campaigns of this type (sent lower case as `type`, e.g. `regular`). Named `campaignType` because `type` is the task type in a flow."
    )
    @PluginProperty(group = "processing")
    private Property<Type> campaignType;

    @Schema(title = "Status", description = "Only campaigns with this status (sent lower case, e.g. `sent`).")
    @PluginProperty(group = "processing")
    private Property<Status> status;

    @Schema(title = "Sent since", description = "Only campaigns sent after this ISO-8601 date and time (`since_send_time`).")
    @PluginProperty(group = "processing")
    private Property<Instant> sinceSendTime;

    @Schema(title = "Sent before", description = "Only campaigns sent before this ISO-8601 date and time (`before_send_time`).")
    @PluginProperty(group = "processing")
    private Property<Instant> beforeSendTime;

    @Schema(title = "Audience ID", description = "Only campaigns sent to this audience (`list_id`).")
    @PluginProperty(group = "processing")
    private Property<String> listId;

    @Schema(title = "Sort field", description = "Field to sort by (sent lower case, e.g. `send_time`).")
    @PluginProperty(group = "advanced")
    private Property<SortField> sortField;

    @Schema(title = "Sort direction", description = "`ASC` or `DESC`; Mailchimp only applies it together with `sortField`.")
    @PluginProperty(group = "advanced")
    private Property<SortDir> sortDir;

    @Schema(
        title = "Fetch strategy",
        description = "`FETCH_ONE`: first campaign only.\n`FETCH` (default): all campaigns in memory.\n`STORE`: write rows as ION to internal storage and return the URI.\n`NONE`: only count them."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Max items", description = "Stop after this many campaigns (default: unlimited). Must be at least 1.")
    @PluginProperty(group = "advanced")
    private Property<Integer> maxItems;

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var query = new LinkedHashMap<String, String>();
        FetchOutput.put(query, "type", runContext.render(campaignType).as(Type.class));
        FetchOutput.put(query, "status", runContext.render(status).as(Status.class));
        FetchOutput.put(query, "since_send_time", runContext.render(sinceSendTime).as(Instant.class));
        FetchOutput.put(query, "before_send_time", runContext.render(beforeSendTime).as(Instant.class));
        FetchOutput.put(query, "list_id", runContext.render(listId).as(String.class));
        FetchOutput.put(query, "sort_field", runContext.render(sortField).as(SortField.class));
        FetchOutput.put(query, "sort_dir", runContext.render(sortDir).as(SortDir.class));

        try (var client = client(runContext)) {
            return FetchOutput.fetchPaged(
                runContext, client, "/campaigns", query, "campaigns",
                runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH),
                runContext.render(maxItems).as(Integer.class).orElse(null),
                item -> {
                    var row = FetchOutput.hoist(FetchOutput.row(item, CAMEL), item.path("recipients"), "list_id");
                    return FetchOutput.hoist(row, item.path("settings"), "subject_line", "title");
                }
            );
        }
    }
}
