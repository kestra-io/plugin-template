package io.kestra.plugin.mailchimp.models;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.models.tasks.Output;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.mailchimp.MailchimpClient;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * Output shared by every task that returns a list of Mailchimp records, shaped by `fetchType`. Also hosts the one
 * shared implementation of row conversion and fetch handling ({@link Collector}, {@link #fetchPaged}).
 */
@Builder
@Getter
public class FetchOutput implements Output {
    static final int PAGE_SIZE = 1000;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    @Schema(title = "First record", description = "Populated when `fetchType` is `FETCH_ONE`.")
    private final Map<String, Object> row;

    @Schema(title = "Records", description = "Populated when `fetchType` is `FETCH`.")
    private final List<Map<String, Object>> rows;

    @Schema(title = "Stored records URI", description = "Populated when `fetchType` is `STORE`: an ION file in internal storage.")
    private final URI uri;

    @Schema(title = "Record count", description = "Number of records read from Mailchimp.")
    private final Long size;

    @Schema(title = "Total count", description = "Total number of matching records reported by Mailchimp (`total_items`), when available.")
    private final Long total;

    /** ISO-8601 with numeric offset (e.g. {@code 2024-01-01T00:00:00+00:00}), as Mailchimp expects. */
    public static String iso(Instant instant) {
        return ISO.format(instant.atOffset(ZoneOffset.UTC));
    }

    /**
     * Adds a rendered optional property as query parameter: {@link Instant} as ISO-8601, {@link SortDir} as is
     * (Mailchimp wants {@code ASC}/{@code DESC}), other enums in lower case, lists comma-joined.
     */
    public static void put(Map<String, String> query, String key, Optional<?> value) {
        value.filter(v -> !(v instanceof List<?> l && l.isEmpty())).ifPresent(v -> query.put(key, switch (v) {
            case Instant i -> iso(i);
            case SortDir d -> d.name();
            case Enum<?> e -> e.name().toLowerCase(Locale.ROOT);
            case List<?> l -> l.stream().map(String::valueOf).collect(Collectors.joining(","));
            default -> String.valueOf(v);
        }));
    }

    public static String camel(String snake) {
        var sb = new StringBuilder();
        var up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    /**
     * Turns a Mailchimp JSON object into a row: keys in {@code camelKeys} are exposed in camelCase, every other
     * top-level key keeps its original name, nested values are left untouched.
     */
    public static Map<String, Object> row(JsonNode node, Set<String> camelKeys) {
        var row = new LinkedHashMap<String, Object>();
        node.fields().forEachRemaining(e -> row.put(
            camelKeys.contains(e.getKey()) ? camel(e.getKey()) : e.getKey(),
            JacksonMapper.ofJson().convertValue(e.getValue(), Object.class)
        ));
        return row;
    }

    /** Copies {@code keys} of a nested object (e.g. {@code stats.member_count}) up into the row in camelCase. */
    public static Map<String, Object> hoist(Map<String, Object> row, JsonNode parent, String... keys) {
        for (var key : keys) {
            if (parent.has(key)) {
                row.put(camel(key), JacksonMapper.ofJson().convertValue(parent.get(key), Object.class));
            }
        }
        return row;
    }

    /**
     * Pages through {@code GET path} (count=1000, smaller when fewer rows are needed) and collects the mapped rows.
     *
     * @param maxItems optional cap on rows read; reading stops early, no further page is requested
     * @param mapper   JSON item to row
     */
    public static FetchOutput fetchPaged(RunContext runContext, MailchimpClient client, String path, Map<String, String> query,
                                         String arrayField, FetchType fetchType, Integer maxItems,
                                         Function<JsonNode, Map<String, Object>> mapper) throws Exception {
        try (var collector = collector(runContext, fetchType, maxItems, arrayField, mapper)) {
            client.getPagedWhile(path, query, (int) Math.min(PAGE_SIZE, collector.cap), collector::accept, arrayField);
            return collector.build();
        }
    }

    public static Collector collector(RunContext runContext, FetchType fetchType, Integer maxItems, String arrayField,
                                      Function<JsonNode, Map<String, Object>> mapper) throws IOException {
        if (maxItems != null && maxItems < 1) {
            throw new IllegalArgumentException("'maxItems' must be >= 1");
        }
        return new Collector(runContext, fetchType, maxItems, arrayField, mapper);
    }

    /** Collects rows as pages arrive: `STORE` streams to a temp file, so only `FETCH` keeps all rows in memory. */
    public static class Collector implements Closeable {
        private final RunContext runContext;
        private final FetchType fetchType;
        private final long cap;
        private final String arrayField;
        private final Function<JsonNode, Map<String, Object>> mapper;
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final Path tempFile;
        private final OutputStream out;
        private long size;
        private Long total;

        private Collector(RunContext runContext, FetchType fetchType, Integer maxItems, String arrayField, Function<JsonNode, Map<String, Object>> mapper) throws IOException {
            this.runContext = runContext;
            this.fetchType = fetchType;
            this.arrayField = arrayField;
            this.mapper = mapper;
            this.cap = fetchType == FetchType.FETCH_ONE ? 1 : (maxItems == null ? Long.MAX_VALUE : maxItems);
            this.tempFile = fetchType == FetchType.STORE ? runContext.workingDir().createTempFile(".ion") : null;
            this.out = tempFile == null ? null : new BufferedOutputStream(Files.newOutputStream(tempFile), FileSerde.BUFFER_SIZE);
        }

        /** Consumes one response page; returns {@code false} once enough rows were read (stop requesting pages). */
        public boolean accept(JsonNode page) {
            if (page.has("total_items")) {
                total = page.get("total_items").asLong();
            }
            try {
                for (var item : page.path(arrayField)) {
                    var row = mapper.apply(item);
                    if (row == null) {
                        continue;
                    }
                    size++;
                    switch (fetchType) {
                        case FETCH, FETCH_ONE -> rows.add(row);
                        case STORE -> FileSerde.write(out, row);
                        case NONE -> { }
                    }
                    if (size >= cap) {
                        return false;
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return true;
        }

        public FetchOutput build() throws IOException {
            var output = FetchOutput.builder().size(size).total(total);
            switch (fetchType) {
                case FETCH_ONE -> output.row(rows.isEmpty() ? null : rows.getFirst());
                case FETCH -> output.rows(rows);
                case STORE -> {
                    close();
                    output.uri(runContext.storage().putFile(tempFile.toFile()));
                }
                case NONE -> { }
            }
            return output.build();
        }

        @Override
        public void close() throws IOException {
            if (out != null) {
                out.close();
            }
        }
    }
}
