package io.kestra.plugin.mailchimp.models;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.mailchimp.MailchimpClient;

/**
 * Polling logic shared by every Mailchimp trigger: cursor in the namespace KV store, incremental query sorted
 * ascending on a timestamp field, client-side dedup through {@link TriggerCursor}, new rows stored as one ION file.
 *
 * <p>Each poll reads ONE page (1000 rows) and moves the cursor to the newest row of it; the next poll restarts from
 * that cursor (minus 1 s). Paging further with {@code offset} inside a poll is unsafe: a row whose timestamp
 * changes mid-poll jumps to the end, the following rows shift left and one is skipped below the advanced cursor
 * for good. The only exception is a full page that does not move the cursor at all (only known rows, e.g. more
 * than 1000 rows in the 1 s slack window or more than 1000 ties in one second): the next poll would read the very
 * same page, so the next page is read in the same poll to guarantee progress. Pages are only chained while no
 * progress was made, so a shift there can only skip a row if it happens in that narrow case. A larger backlog
 * therefore drains at 1000 rows per poll.
 */
public final class CursorPoll {
    private static final Comparator<TriggerCursor.Item> ORDER =
        Comparator.comparing(TriggerCursor.Item::timestamp).thenComparing(TriggerCursor.Item::id);

    private CursorPoll() {
    }

    /**
     * @param kind       trigger kind in the KV key, e.g. {@code new_subscriber}, {@code member_status_<status>}
     * @param scope      last KV key segment: the list id, or {@code campaign_<listId or all>}
     * @param query      fixed filters (status, list_id...); {@code since_<timeField>} and the sort are added here
     * @param timeField  Mailchimp timestamp field the cursor follows, e.g. {@code timestamp_opt}
     * @param filter     rows failing it do not fire; the cursor still moves past them
     */
    public record Spec(String kind, String scope, String path, String arrayField, Map<String, String> query,
                       String timeField, Predicate<JsonNode> filter, Function<JsonNode, Map<String, Object>> mapper) {
    }

    /** New rows of one poll. Call {@link #commit()} once the execution is built to persist the advanced cursor. */
    public record Result(long count, URI uri, TriggerCursor cursor, Map<String, Object> latest, KVStore kv, String key) {
        public void commit() throws IOException {
            put(kv, key, cursor);
        }
    }

    /**
     * First poll (or unreadable stored cursor): records the newest current position and returns empty, so what
     * already exists never fires. Later polls return the rows that are new for the stored cursor, or empty.
     * A Mailchimp error propagates before anything is written.
     */
    public static Optional<Result> poll(RunContext runContext, TriggerContext context, MailchimpClient client, Spec spec) throws Exception {
        var logger = runContext.logger();
        var kv = runContext.namespaceKv(context.getNamespace());
        // length-prefixed flow and trigger ids: no collision across flows or segment splits
        var flowId = context.getFlowId();
        var triggerId = context.getTriggerId();
        var key = "mailchimp_" + spec.kind() + "_" + flowId.length() + "_" + flowId + "_" + triggerId.length() + "_" + triggerId + "_" + spec.scope();
        var stored = kv.getValue(key).map(KVValue::value).flatMap(v -> TriggerCursor.tryParse(String.valueOf(v)));

        if (stored.isEmpty()) {
            var query = new LinkedHashMap<>(spec.query());
            query.put("sort_field", spec.timeField());
            query.put("sort_dir", "DESC");
            query.put("count", String.valueOf(FetchOutput.PAGE_SIZE));
            query.put("offset", "0");
            var items = new ArrayList<TriggerCursor.Item>();
            for (var node : client.send("GET", spec.path(), query, null).path(spec.arrayField())) {
                item(node, spec, logger).ifPresent(items::add);
            }
            // empty list: start at the epoch, so the first row ever added fires whatever its timestamp
            var start = new TriggerCursor(Instant.EPOCH, new TreeSet<>());
            put(kv, key, items.isEmpty() ? start : start.advance(items));
            return Optional.empty();
        }

        var cursor = stored.get();
        var query = new LinkedHashMap<>(spec.query());
        // 1 s slack: observed against the live API, `since_*` is exclusive and has whole-second precision, so a row written
        // later in the cursor's own second would be missed without it; the rows it brings back are dropped by the cursor
        var since = cursor.timestamp().minusSeconds(1);
        query.put("since_" + spec.timeField(), FetchOutput.iso(since.isBefore(Instant.EPOCH) ? Instant.EPOCH : since));
        query.put("sort_field", spec.timeField());
        query.put("sort_dir", "ASC");

        var read = new ArrayList<TriggerCursor.Item>(); // every dated row read: the cursor moves past all of them
        var fired = new ArrayList<TriggerCursor.Item>();
        var seen = new HashSet<String>();
        var latest = new Object() {
            TriggerCursor.Item item;
            Map<String, Object> row;
        };
        try (var collector = FetchOutput.collector(runContext, FetchType.STORE, null, spec.arrayField(), node -> {
            var item = item(node, spec, logger);
            item.ifPresent(read::add);
            item = item.filter(i -> spec.filter().test(node) && cursor.isNew(i.timestamp(), i.id()) && seen.add(i.id()));
            if (item.isEmpty()) {
                return null;
            }
            fired.add(item.get());
            var row = spec.mapper().apply(node);
            if (latest.item == null || ORDER.compare(item.get(), latest.item) > 0) {
                latest.item = item.get();
                latest.row = row;
            }
            return row;
        })) {
            client.getPagedWhile(spec.path(), query, FetchOutput.PAGE_SIZE, page -> {
                collector.accept(page);
                // next page only while a full page brought nothing that moves the cursor: guarantees progress
                return page.path(spec.arrayField()).size() >= FetchOutput.PAGE_SIZE && cursor.advance(read).equals(cursor);
            }, spec.arrayField());
            var next = cursor.advance(read);
            if (fired.isEmpty()) {
                // only ignored rows (other status...): still move past them so they are not read again and again
                if (!next.equals(cursor)) {
                    put(kv, key, next);
                }
                return Optional.empty();
            }
            var out = collector.build();
            return Optional.of(new Result(out.getSize(), out.getUri(), next, latest.row, kv, key));
        }
    }

    private static Optional<Instant> time(JsonNode node, String timeField) {
        try {
            return Optional.of(OffsetDateTime.parse(node.path(timeField).asText("")).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Row id and timestamp, or empty with a warning when either is missing or invalid. */
    private static Optional<TriggerCursor.Item> item(JsonNode node, Spec spec, Logger logger) {
        var id = node.path("id").asText("");
        var raw = node.path(spec.timeField()).asText("");
        if (id.isEmpty()) {
            logger.warn("Skipping a Mailchimp record without id");
            return Optional.empty();
        }
        var time = time(node, spec.timeField());
        if (time.isEmpty()) {
            logger.warn("Skipping Mailchimp record '{}': '{}' is not an ISO-8601 date-time: '{}'", id, spec.timeField(), raw);
        }
        return time.map(t -> new TriggerCursor.Item(t, id));
    }

    private static void put(KVStore kv, String key, TriggerCursor cursor) throws IOException {
        kv.put(key, new KVValueAndMetadata(new KVMetadata(null, (Duration) null), cursor.format()));
    }
}
