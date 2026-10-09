package io.kestra.plugin.mailchimp;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.triggers.Trigger;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Recorded;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;
import io.kestra.plugin.mailchimp.models.TriggerCursor;

/** Shared helpers of the polling trigger tests. */
public final class TriggerSupport {
    private TriggerSupport() {
    }

    /** {@code <field>:[items...]} page as Mailchimp returns it. */
    public static String page(String field, String... items) {
        return "{\"" + field + "\":[" + String.join(",", items) + "],\"total_items\":" + items.length + "}";
    }

    public static String member(String id, String timeField, String time) {
        return member(id, timeField, time, "subscribed");
    }

    public static String member(String id, String timeField, String time, String status) {
        return "{\"id\":\"" + id + "\",\"email_address\":\"" + id + "@example.com\",\"status\":\"" + status + "\",\"" + timeField + "\":\"" + time + "\"}";
    }

    /** Stored cursor under the documented length-prefixed key. */
    public static Optional<TriggerCursor> cursor(Map.Entry<ConditionContext, Trigger> ctx, String kind, String scope) throws Exception {
        var t = ctx.getValue();
        var key = "mailchimp_" + kind + "_" + t.getFlowId().length() + "_" + t.getFlowId() + "_" + t.getTriggerId().length() + "_" + t.getTriggerId() + "_" + scope;
        return ctx.getKey().getRunContext().namespaceKv(t.getNamespace()).getValue(key)
            .map(KVValue::value).flatMap(v -> TriggerCursor.tryParse(String.valueOf(v)));
    }

    public static Map<String, Object> vars(Execution execution) {
        return execution.getTrigger().getVariables();
    }

    public static List<String> ids(Map.Entry<ConditionContext, Trigger> ctx, Execution execution) throws Exception {
        var rows = ReadTaskSupport.readIon(ctx.getKey().getRunContext(), URI.create(String.valueOf(vars(execution).get("uri"))));
        return rows.stream().map(r -> String.valueOf(r.get("id"))).collect(Collectors.toList());
    }

    /** A record of the simulated Mailchimp list; mutable so a test can move it while the trigger pages. */
    public static final class Row {
        public final String id;
        public Instant time;
        public String status;

        public Row(String id, Instant time, String status) {
            this.id = id;
            this.time = time;
            this.status = status;
        }
    }

    /**
     * Simulates a Mailchimp list endpoint over {@code rows}: {@code since_<timeField>} inclusive, {@code status}
     * filter, stable sort on {@code timeField} by {@code sort_dir}, then {@code offset}/{@code count}.
     */
    public static Function<Recorded, Response> listServer(String arrayField, String timeField, List<Row> rows) {
        return request -> {
            var q = new HashMap<String, String>();
            for (var pair : request.query().split("&")) {
                var kv = pair.split("=", 2);
                q.put(kv[0], URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
            var since = Optional.ofNullable(q.get("since_" + timeField)).map(v -> OffsetDateTime.parse(v).toInstant());
            var selected = new ArrayList<Row>();
            for (var row : rows) {
                if (since.map(t -> !row.time.isBefore(t)).orElse(true) && (!q.containsKey("status") || q.get("status").equals(row.status))) {
                    selected.add(row);
                }
            }
            Comparator<Row> order = Comparator.comparing(r -> r.time);
            selected.sort("DESC".equals(q.get("sort_dir")) ? order.reversed() : order);
            int offset = Integer.parseInt(q.getOrDefault("offset", "0"));
            int count = Integer.parseInt(q.getOrDefault("count", "10"));
            var items = selected.subList(Math.min(offset, selected.size()), Math.min(offset + count, selected.size())).stream()
                .map(r -> member(r.id, timeField, r.time.toString(), r.status)).collect(Collectors.joining(","));
            return Response.json(200, "{\"" + arrayField + "\":[" + items + "],\"total_items\":" + selected.size() + "}");
        };
    }

    /** Polls until a poll fires nothing (at most {@code maxPolls}) and returns every fired id, in firing order. */
    public static List<String> drain(io.kestra.core.models.triggers.PollingTriggerInterface trigger, Map.Entry<ConditionContext, Trigger> ctx, int maxPolls) throws Exception {
        var fired = new ArrayList<String>();
        for (int i = 0; i < maxPolls; i++) {
            var execution = trigger.evaluate(ctx.getKey(), ctx.getValue());
            if (execution.isEmpty()) {
                return fired;
            }
            fired.addAll(ids(ctx, execution.get()));
        }
        throw new AssertionError("still firing after " + maxPolls + " polls");
    }
}
