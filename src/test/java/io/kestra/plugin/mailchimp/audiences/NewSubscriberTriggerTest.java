package io.kestra.plugin.mailchimp.audiences;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.Trigger;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;
import io.kestra.plugin.mailchimp.MailchimpException;
import io.kestra.plugin.mailchimp.TriggerSupport.Row;
import io.kestra.plugin.mailchimp.models.TriggerCursor;

import jakarta.inject.Inject;

import static io.kestra.plugin.mailchimp.TriggerSupport.cursor;
import static io.kestra.plugin.mailchimp.TriggerSupport.drain;
import static io.kestra.plugin.mailchimp.TriggerSupport.listServer;
import static io.kestra.plugin.mailchimp.TriggerSupport.ids;
import static io.kestra.plugin.mailchimp.TriggerSupport.page;
import static io.kestra.plugin.mailchimp.TriggerSupport.vars;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class NewSubscriberTriggerTest {
    private static final String PATH = "/3.0/lists/L1/members";
    private static final String KIND = "new_subscriber";
    private static final Instant B = Instant.parse("2026-01-01T10:00:00Z");

    @Inject
    private RunContextFactory runContextFactory;

    private static String m(String id, String second) {
        return io.kestra.plugin.mailchimp.TriggerSupport.member(id, "timestamp_opt", "2026-01-01T10:00:" + second + "+00:00");
    }

    private static Instant at(String second) {
        return Instant.parse("2026-01-01T10:00:" + second + "Z");
    }

    private NewSubscriberTrigger.NewSubscriberTriggerBuilder<?, ?> trigger(FakeMailchimpServer fake) {
        return NewSubscriberTrigger.builder()
            .id("new-" + UUID.randomUUID())
            .type(NewSubscriberTrigger.class.getName())
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .maxRetries(Property.ofValue(0))
            .listId(Property.ofValue("L1"));
    }

    private Map.Entry<ConditionContext, Trigger> ctx(NewSubscriberTrigger trigger) {
        return TestsUtils.mockTrigger(runContextFactory, trigger);
    }

    @Test
    void firstPollFiresNothingAndStoresNewestPosition() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            fake.on("GET", PATH, Response.json(200, page("members", m("m2", "05"), m("m1", "00"))));

            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));

            assertThat(cursor(ctx, KIND, "L1").orElseThrow(), equalTo(new TriggerCursor(at("05"), new java.util.TreeSet<>(List.of("m2")))));
            assertThat(fake.requests().getFirst().query(), equalTo("status=subscribed&sort_field=timestamp_opt&sort_dir=DESC&count=1000&offset=0"));
        }
    }

    @Test
    void secondPollFiresOneExecutionWithAllNewRowsThenNothing() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            fake.on("GET", PATH, Response.json(200, page("members", m("m1", "05"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());

            // inclusive server: the boundary row m1 comes back too and must not refire
            fake.on("GET", PATH, Response.json(200, page("members", m("m1", "05"), m("m2", "10"), m("m3", "10"))));
            var execution = trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow();

            assertThat(((Number) vars(execution).get("count")).intValue(), is(2));
            assertThat(vars(execution).get("listId"), is("L1"));
            assertThat(ids(ctx, execution), contains("m2", "m3"));
            var stored = cursor(ctx, KIND, "L1").orElseThrow();
            assertThat(stored, equalTo(new TriggerCursor(at("10"), new java.util.TreeSet<>(List.of("m2", "m3")))));
            assertThat(vars(execution).get("cursor"), is(stored.format()));

            // third poll, nothing changed
            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
        }
    }

    @Test
    void requestCarriesSortAndSinceWithOneSecondSlack() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            fake.on("GET", PATH, Response.json(200, page("members", m("m1", "05"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            trigger.evaluate(ctx.getKey(), ctx.getValue());

            assertThat(fake.requests().get(1).query(), equalTo(
                "status=subscribed&since_timestamp_opt=2026-01-01T10%3A00%3A04%2B00%3A00"
                    + "&sort_field=timestamp_opt&sort_dir=ASC&count=1000&offset=0"));
        }
    }

    @Test
    void boundaryTieRowsArrivingInDifferentPollsEachFireOnce() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            fake.on("GET", PATH, Response.json(200, page("members", m("m0", "00"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());

            fake.on("GET", PATH, Response.json(200, page("members", m("m1", "10"))));
            assertThat(ids(ctx, trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow()), contains("m1"));

            // same timestamp_opt as m1, visible only now
            fake.on("GET", PATH, Response.json(200, page("members", m("m1", "10"), m("m2", "10"))));
            assertThat(ids(ctx, trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow()), contains("m2"));

            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
        }
    }

    @Test
    void emptyListIsNotAnError() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            fake.on("GET", PATH, Response.json(200, page("members")));

            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
            assertThat(cursor(ctx, KIND, "L1").orElseThrow(), equalTo(new TriggerCursor(Instant.EPOCH, new java.util.TreeSet<>())));
            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
            // since never goes below the epoch
            assertThat(fake.requests().get(1).query(), containsString("since_timestamp_opt=1970-01-01T00%3A00%3A00%2B00%3A00&"));
        }
    }

    @Test
    void firstPollEmptyThenRowOlderThanNowFiresOnce() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            var rows = new ArrayList<Row>();
            fake.handle("GET", PATH, listServer("members", "timestamp_opt", rows));
            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));

            rows.add(new Row("m1", Instant.parse("2020-01-01T00:00:00Z"), "subscribed"));

            assertThat(drain(trigger, ctx, 5), contains("m1"));
        }
    }

    @Test
    void backlogOf2500DrainsAcrossPollsWithEveryRowFiredOnce() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            var rows = new ArrayList<Row>(List.of(new Row("old", B, "subscribed")));
            fake.handle("GET", PATH, listServer("members", "timestamp_opt", rows));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            var expected = new ArrayList<String>();
            for (int i = 1; i <= 2500; i++) {
                rows.add(new Row(String.format("r%04d", i), B.plusSeconds(i), "subscribed"));
                expected.add(String.format("r%04d", i));
            }

            var first = trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow();
            // one page per poll: the boundary row `old` plus 999 new ones
            assertThat(((Number) vars(first).get("count")).intValue(), is(999));
            var fired = new ArrayList<>(ids(ctx, first));
            fired.addAll(drain(trigger, ctx, 10));

            assertThat(fired, equalTo(expected));
        }
    }

    @Test
    void rowMovingWhilePagingIsNotLost() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            var rows = new ArrayList<Row>(List.of(new Row("old", B, "subscribed")));
            var server = listServer("members", "timestamp_opt", rows);
            var moved = new AtomicBoolean();
            fake.handle("GET", PATH, request -> {
                var response = server.apply(request);
                // once the first incremental page is served, r0010 changes and jumps to the end of the ASC order
                if (request.query().contains("since_") && moved.compareAndSet(false, true)) {
                    rows.get(10).time = B.plusSeconds(5000);
                }
                return response;
            });
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            for (int i = 1; i <= 1500; i++) {
                rows.add(new Row(String.format("r%04d", i), B.plusSeconds(i), "subscribed"));
            }

            var fired = drain(trigger, ctx, 10);

            for (int i = 1; i <= 1500; i++) {
                var id = String.format("r%04d", i);
                assertThat(id, Collections.frequency(fired, id), is(id.equals("r0010") ? 2 : 1));
            }
        }
    }

    @Test
    void moreThanAPageOfTiesStillMakesProgress() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            var rows = new ArrayList<Row>(List.of(new Row("old", B, "subscribed")));
            fake.handle("GET", PATH, listServer("members", "timestamp_opt", rows));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            for (int i = 0; i < 1200; i++) {
                rows.add(new Row(String.format("t%04d", i), B.plusSeconds(10), "subscribed"));
            }
            for (int i = 0; i < 5; i++) {
                rows.add(new Row("u" + i, B.plusSeconds(20), "subscribed"));
            }

            var fired = drain(trigger, ctx, 10);

            assertThat(fired.size(), is(1205));
            assertThat(new HashSet<>(fired).size(), is(1205));
        }
    }

    @Test
    void intervalBelowThirtySecondsThrows() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).interval(Duration.ofSeconds(10)).build();
            var ctx = ctx(trigger);

            var e = assertThrows(IllegalArgumentException.class, () -> trigger.evaluate(ctx.getKey(), ctx.getValue()));
            assertThat(e.getMessage(), containsString("PT30S"));
            assertThat(e.getMessage(), containsString("PT10S"));
            assertThat(trigger(fake).build().getInterval(), is(Duration.ofMinutes(5)));
        }
    }

    @Test
    void transientErrorDoesNotAdvanceCursor() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            fake.on("GET", PATH, Response.json(200, page("members", m("m1", "05"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            var stored = cursor(ctx, KIND, "L1").orElseThrow();

            fake.on("GET", PATH, Response.json(503, "{\"title\":\"Service Unavailable\"}"));
            assertThrows(MailchimpException.class, () -> trigger.evaluate(ctx.getKey(), ctx.getValue()));
            assertThat(cursor(ctx, KIND, "L1").orElseThrow(), equalTo(stored));

            fake.on("GET", PATH, Response.json(200, page("members", m("m2", "06"))));
            assertThat(ids(ctx, trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow()), contains("m2"));
        }
    }

    @Test
    void rowWithBadTimestampIsSkipped() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            fake.on("GET", PATH, Response.json(200, page("members", m("m1", "05"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());

            fake.on("GET", PATH, Response.json(200, page("members",
                "{\"id\":\"bad1\",\"timestamp_opt\":\"\"}",
                "{\"id\":\"bad2\",\"timestamp_opt\":\"yesterday\"}",
                "{\"id\":\"bad3\"}",
                m("m2", "10"))));
            var execution = trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow();

            assertThat(ids(ctx, execution), contains("m2"));
        }
    }

    @Test
    void corruptStoredCursorCountsAsFirstPoll() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = ctx(trigger);
            var t = ctx.getValue();
            var key = "mailchimp_" + KIND + "_" + t.getFlowId().length() + "_" + t.getFlowId() + "_" + t.getTriggerId().length() + "_" + t.getTriggerId() + "_L1";
            ctx.getKey().getRunContext().namespaceKv(t.getNamespace()).put(key, new KVValueAndMetadata(new KVMetadata(null, (Duration) null), "garbage"));
            fake.on("GET", PATH, Response.json(200, page("members", m("m1", "05"))));

            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
            assertThat(cursor(ctx, KIND, "L1").orElseThrow().timestamp(), is(at("05")));
        }
    }

    @Test
    void slackWindowWithMoreThanAPageOfKnownRowsStillDrains() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            var rows = new ArrayList<Row>(List.of(new Row("old", B, "subscribed")));
            fake.handle("GET", PATH, listServer("members", "timestamp_opt", rows));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            // 1500 rows at second 9 and 1500 at second 10: every poll re-reads > 1000 known rows in the 1 s slack window
            for (int i = 0; i < 3000; i++) {
                rows.add(new Row(String.format("r%04d", i), B.plusSeconds(i < 1500 ? 9 : 10), "subscribed"));
            }

            var fired = drain(trigger, ctx, 20);

            assertThat(fired.size(), is(3000));
            assertThat(new HashSet<>(fired).size(), is(3000));
        }
    }

    @Test
    void oneRowBeforeAndMoreThanAPageAtTheCursorSecondStillDrains() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            var rows = new ArrayList<Row>(List.of(new Row("old", B, "subscribed")));
            fake.handle("GET", PATH, listServer("members", "timestamp_opt", rows));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            rows.add(new Row("before", B.plusSeconds(9), "subscribed"));
            for (int i = 0; i < 1200; i++) {
                rows.add(new Row(String.format("t%04d", i), B.plusSeconds(10), "subscribed"));
            }

            var fired = drain(trigger, ctx, 20);

            assertThat(fired.size(), is(1201));
            assertThat(new HashSet<>(fired).size(), is(1201));
        }
    }
}
