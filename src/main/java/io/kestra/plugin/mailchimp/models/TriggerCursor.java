package io.kestra.plugin.mailchimp.models;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.serializers.JacksonMapper;

/**
 * Position of a polling trigger: the newest timestamp seen plus the ids of every item seen at exactly that
 * timestamp. Items sharing a timestamp across two polls are then neither skipped nor fired twice, whether the
 * Mailchimp {@code since_*} filter is inclusive or exclusive.
 */
public record TriggerCursor(Instant timestamp, SortedSet<String> idsAtTimestamp) {
    public record Item(Instant timestamp, String id) {
    }

    public TriggerCursor {
        idsAtTimestamp = new TreeSet<>(idsAtTimestamp);
    }

    public boolean isNew(Instant ts, String id) {
        var cmp = ts.compareTo(timestamp);
        return cmp > 0 || (cmp == 0 && !idsAtTimestamp.contains(id));
    }

    /** The cursor after {@code items} were fired. */
    public TriggerCursor advance(Collection<Item> items) {
        var max = items.stream().map(Item::timestamp).reduce(timestamp, (a, b) -> a.compareTo(b) >= 0 ? a : b);
        var ids = new TreeSet<String>(max.equals(timestamp) ? idsAtTimestamp : List.of());
        items.stream().filter(i -> i.timestamp().equals(max)).forEach(i -> ids.add(i.id()));
        return new TriggerCursor(max, ids);
    }

    public String format() {
        try {
            return JacksonMapper.ofJson().writeValueAsString(Map.of("timestamp", timestamp.toString(), "ids", idsAtTimestamp));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A corrupted stored value gives empty, so the trigger restarts as on a first poll instead of failing every poll. */
    public static Optional<TriggerCursor> tryParse(String stored) {
        try {
            JsonNode node = JacksonMapper.ofJson().readTree(stored);
            var ids = new TreeSet<String>();
            node.get("ids").forEach(id -> ids.add(id.asText()));
            return Optional.of(new TriggerCursor(Instant.parse(node.get("timestamp").asText()), ids));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
