package io.kestra.plugin.mailchimp.audiences;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.mailchimp.AbstractMailchimpTask;
import io.kestra.plugin.mailchimp.models.FetchOutput;

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
    title = "List Mailchimp audiences",
    description = """
        Reads the audiences (lists) of the account with `GET /lists`.

        By default every page is read (1000 per request) until `maxItems` or the end. If `count` or `offset` is set, a single request honoring them is made and no further page is read.

        Each row exposes `id`, `name`, `dateCreated`, `visibility`, `memberCount` and `unsubscribeCount` (the latter two come from `stats`); every other Mailchimp field keeps its original key."""
)
@Plugin(
    examples = {
        @Example(
            title = "List all audiences created this year and keep them in memory.",
            full = true,
            code = """
                id: mailchimp_list_audiences
                namespace: company.team

                tasks:
                  - id: audiences
                    type: io.kestra.plugin.mailchimp.audiences.ListAudiences
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                    sinceDateCreated: "2026-01-01T00:00:00Z"
                    fetchType: FETCH
                """
        )
    }
)
public class ListAudiences extends AbstractMailchimpTask implements RunnableTask<FetchOutput> {
    private static final Set<String> CAMEL = Set.of("date_created");

    @Schema(
        title = "Fetch strategy",
        description = "`FETCH_ONE`: first audience only.\n`FETCH` (default): all audiences in memory.\n`STORE`: write rows as ION to internal storage and return the URI.\n`NONE`: only count them."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

    @Schema(
        title = "Page size",
        description = "Rows per request. When `count` or `offset` is set the task does a single request and does not paginate; otherwise it pages with 1000 per request."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> count;

    @Schema(
        title = "Offset",
        description = "Rows to skip. When `count` or `offset` is set the task does a single request and does not paginate."
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> offset;

    @Schema(title = "Created since", description = "Only audiences created after this ISO-8601 date and time (sent as `since_date_created`).")
    @PluginProperty(group = "processing")
    private Property<Instant> sinceDateCreated;

    @Schema(title = "Created before", description = "Only audiences created before this ISO-8601 date and time (sent as `before_date_created`).")
    @PluginProperty(group = "processing")
    private Property<Instant> beforeDateCreated;

    @Schema(title = "Include total contacts", description = "Also ask Mailchimp for the total contact count of each audience (`include_total_contacts`).")
    @PluginProperty(group = "processing")
    private Property<Boolean> includeTotalContacts;

    @Schema(
        title = "Fields",
        description = "Restrict the response to these Mailchimp fields, using Mailchimp's dotted syntax, e.g. `lists.id`, `lists.name`, `total_items`."
    )
    @PluginProperty(group = "advanced")
    private Property<List<String>> fields;

    @Schema(title = "Max items", description = "Stop after this many audiences (default: unlimited). Must be at least 1.")
    @PluginProperty(group = "advanced")
    private Property<Integer> maxItems;

    @Override
    public FetchOutput run(RunContext runContext) throws Exception {
        var rFetchType = runContext.render(fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rMaxItems = runContext.render(maxItems).as(Integer.class).orElse(null);
        var rCount = runContext.render(count).as(Integer.class);
        var rOffset = runContext.render(offset).as(Integer.class);
        if (rCount.isPresent() && (rCount.get() < 1 || rCount.get() > 1000)) {
            throw new IllegalArgumentException("'count' must be between 1 and 1000");
        }
        if (rOffset.isPresent() && rOffset.get() < 0) {
            throw new IllegalArgumentException("'offset' must be >= 0");
        }

        var query = new LinkedHashMap<String, String>();
        FetchOutput.put(query, "since_date_created", runContext.render(sinceDateCreated).as(Instant.class));
        FetchOutput.put(query, "before_date_created", runContext.render(beforeDateCreated).as(Instant.class));
        FetchOutput.put(query, "include_total_contacts", runContext.render(includeTotalContacts).as(Boolean.class).filter(Boolean::booleanValue));
        FetchOutput.put(query, "fields", Optional.of(runContext.render(fields).asList(String.class)));

        try (var client = client(runContext)) {
            Function<JsonNode, Map<String, Object>> mapper = item ->
                FetchOutput.hoist(FetchOutput.row(item, CAMEL), item.path("stats"), "member_count", "unsubscribe_count");

            if (rCount.isEmpty() && rOffset.isEmpty()) {
                return FetchOutput.fetchPaged(runContext, client, "/lists", query, "lists", rFetchType, rMaxItems, mapper);
            }
            query.put("count", String.valueOf(rCount.orElse(1000)));
            query.put("offset", String.valueOf(rOffset.orElse(0)));
            try (var collector = FetchOutput.collector(runContext, rFetchType, rMaxItems, "lists", mapper)) {
                collector.accept(client.send("GET", "/lists", query, null));
                return collector.build();
            }
        }
    }
}
