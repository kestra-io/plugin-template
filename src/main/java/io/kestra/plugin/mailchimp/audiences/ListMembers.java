package io.kestra.plugin.mailchimp.audiences;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

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
import io.kestra.plugin.mailchimp.models.SortDir;

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
    title = "List members of a Mailchimp audience",
    description = """
        Reads the members of an audience with `GET /lists/{listId}/members`, 1000 per request, until `maxItems` or the end. Use `fetchType: STORE` for large audiences: rows are streamed to an ION file instead of kept in memory.

        Each row exposes `id`, `emailAddress`, `status`, `mergeFields` (nested keys such as `FNAME` are unchanged), `tags`, `timestampOpt`, `lastChanged`, `language` and `vip`; every other Mailchimp field keeps its original key."""
)
@Plugin(
    examples = {
        @Example(
            title = "Store all subscribed members of an audience, changed since a date, as an ION file.",
            full = true,
            code = """
                id: mailchimp_list_members
                namespace: company.team

                tasks:
                  - id: members
                    type: io.kestra.plugin.mailchimp.audiences.ListMembers
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    listId: "a1b2c3d4e5"
                    status: SUBSCRIBED
                    sinceLastChanged: "2026-01-01T00:00:00Z"
                    fetchType: STORE
                """
        )
    }
)
public class ListMembers extends AbstractMailchimpTask implements RunnableTask<FetchOutput> {
    private static final Set<String> CAMEL = Set.of("email_address", "merge_fields", "timestamp_opt", "last_changed");

    public enum Status { SUBSCRIBED, UNSUBSCRIBED, CLEANED, PENDING, TRANSACTIONAL, ARCHIVED }

    public enum SortField { TIMESTAMP_OPT, TIMESTAMP_SIGNUP, LAST_CHANGED }

    @Schema(title = "Audience ID", description = "Mailchimp audience (list) id, found in the audience settings or with `ListAudiences`.")
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> listId;

    @Schema(title = "Status", description = "Only members with this status (sent lower case, e.g. `subscribed`).")
    @PluginProperty(group = "processing")
    private Property<Status> status;

    @Schema(title = "Changed since", description = "Only members changed after this ISO-8601 date and time (`since_last_changed`).")
    @PluginProperty(group = "processing")
    private Property<Instant> sinceLastChanged;

    @Schema(title = "Opted in since", description = "Only members who opted in after this ISO-8601 date and time (`since_timestamp_opt`).")
    @PluginProperty(group = "processing")
    private Property<Instant> sinceTimestampOpt;

    @Schema(title = "Sort field", description = "Field to sort by (sent lower case, e.g. `timestamp_opt`).")
    @PluginProperty(group = "advanced")
    private Property<SortField> sortField;

    @Schema(title = "Sort direction", description = "`ASC` or `DESC`; Mailchimp only applies it together with `sortField`.")
    @PluginProperty(group = "advanced")
    private Property<SortDir> sortDir;

    @Schema(
        title = "Fields",
        description = "Restrict the response to these Mailchimp fields, using Mailchimp's dotted syntax, e.g. `members.id`, `members.email_address`, `total_items`."
    )
    @PluginProperty(group = "advanced")
    private Property<List<String>> fields;

    @Schema(
        title = "Fetch strategy",
        description = "`FETCH_ONE`: first member only.\n`FETCH` (default): all members in memory.\n`STORE`: write rows as ION to internal storage and return the URI.\n`NONE`: only count them."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(title = "Max items", description = "Stop after this many members (default: unlimited). Must be at least 1. Use it to bound a huge audience.")
    @PluginProperty(group = "advanced")
    private Property<Integer> maxItems;

    /** Member JSON to row; shared with the member triggers. */
    static Map<String, Object> toRow(JsonNode item) {
        return FetchOutput.row(item, CAMEL);
    }

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var rListId = runContext.render(listId).as(String.class).orElseThrow(() -> new IllegalArgumentException("'listId' is required"));
        var path = "/lists/" + MailchimpClient.segment(rListId) + "/members";

        var query = new LinkedHashMap<String, String>();
        FetchOutput.put(query, "status", runContext.render(status).as(Status.class));
        FetchOutput.put(query, "since_last_changed", runContext.render(sinceLastChanged).as(Instant.class));
        FetchOutput.put(query, "since_timestamp_opt", runContext.render(sinceTimestampOpt).as(Instant.class));
        FetchOutput.put(query, "sort_field", runContext.render(sortField).as(SortField.class));
        FetchOutput.put(query, "sort_dir", runContext.render(sortDir).as(SortDir.class));
        FetchOutput.put(query, "fields", Optional.of(runContext.render(fields).asList(String.class)));

        try (var client = client(runContext)) {
            return FetchOutput.fetchPaged(
                runContext, client, path, query, "members",
                runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH),
                runContext.render(maxItems).as(Integer.class).orElse(null),
                ListMembers::toRow
            );
        }
    }
}
