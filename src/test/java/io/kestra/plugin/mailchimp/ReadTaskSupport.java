package io.kestra.plugin.mailchimp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;

import reactor.core.publisher.Flux;

/** Shared helpers of the read-task tests. */
public final class ReadTaskSupport {
    private ReadTaskSupport() {
    }

    /** A Mailchimp list page with {@code n} items {@code {"id":"i<from>"}...} and the given {@code total_items}. */
    public static String page(String field, int from, int n, int total) {
        var items = IntStream.range(from, from + n).mapToObj(i -> "{\"id\":\"i" + i + "\"}").collect(Collectors.joining(","));
        return "{\"" + field + "\":[" + items + "],\"total_items\":" + total + "}";
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> readIon(RunContext runContext, URI uri) throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(runContext.storage().getFile(uri), StandardCharsets.UTF_8))) {
            return Flux.from(FileSerde.readAll(reader)).map(o -> (Map<String, Object>) o).collectList().block();
        }
    }
}
