package io.kestra.plugin.mailchimp.audiences;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;
import io.kestra.plugin.mailchimp.TriggerSupport;
import io.kestra.plugin.mailchimp.TriggerSupport.Row;

import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolationException;

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
class MemberStatusChangeTriggerTest {
    private static final String PATH = "/3.0/lists/L1/members";
    private static final Instant B = Instant.parse("2026-01-01T10:00:00Z");

    @Inject
    private RunContextFactory runContextFactory;

    private static String m(String id, String second, String status) {
        return TriggerSupport.member(id, "last_changed", "2026-01-01T10:00:" + second + "+00:00", status);
    }

    private MemberStatusChangeTrigger.MemberStatusChangeTriggerBuilder<?, ?> trigger(FakeMailchimpServer fake) {
        return MemberStatusChangeTrigger.builder()
            .id("status-" + UUID.randomUUID())
            .type(MemberStatusChangeTrigger.class.getName())
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .maxRetries(Property.ofValue(0))
            .listId(Property.ofValue("L1"))
            .status(Property.ofValue(MemberStatusChangeTrigger.Status.UNSUBSCRIBED));
    }

    @Test
    void firesOnlyRowsWithTheWatchedStatus() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            fake.on("GET", PATH, Response.json(200, page("members", m("u0", "05", "unsubscribed"))));
            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
            assertThat(cursor(ctx, "member_status_unsubscribed", "L1").isPresent(), is(true));

            fake.on("GET", PATH, Response.json(200, page("members",
                m("u0", "05", "unsubscribed"),
                m("u1", "10", "unsubscribed"),
                m("s1", "11", "subscribed"),
                m("u2", "12", "unsubscribed"))));
            var execution = trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow();

            assertThat(((Number) vars(execution).get("count")).intValue(), is(2));
            assertThat(ids(ctx, execution), contains("u1", "u2"));
            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
        }
    }

    @Test
    void requestFiltersStatusAndSortsByLastChanged() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            fake.on("GET", PATH, Response.json(200, page("members", m("u0", "05", "unsubscribed"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            trigger.evaluate(ctx.getKey(), ctx.getValue());

            assertThat(fake.requests().get(0).query(), equalTo("status=unsubscribed&sort_field=last_changed&sort_dir=DESC&count=1000&offset=0"));
            assertThat(fake.requests().get(1).query(), equalTo(
                "status=unsubscribed&since_last_changed=2026-01-01T10%3A00%3A04%2B00%3A00"
                    + "&sort_field=last_changed&sort_dir=ASC&count=1000&offset=0"));
        }
    }

    @Test
    void statusIsRequired() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).status(null).build();

            // @NotNull: rejected by bean validation, before any request
            var e = assertThrows(ConstraintViolationException.class, () -> {
                var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
                trigger.evaluate(ctx.getKey(), ctx.getValue());
            });
            assertThat(fake.requests().isEmpty(), is(true));
            assertThat(e.getConstraintViolations().stream().map(v -> v.getPropertyPath().toString()).toList(), contains("status"));
        }
    }

    @Test
    void intervalBelowThirtySecondsThrows() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).interval(Duration.ofSeconds(29)).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

            assertThrows(IllegalArgumentException.class, () -> trigger.evaluate(ctx.getKey(), ctx.getValue()));
        }
    }

    @Test
    void changingStatusStartsAFreshCursor() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            fake.on("GET", PATH, Response.json(200, page("members", m("u0", "05", "unsubscribed"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());

            // same flow and trigger id, now watching `cleaned`: its own first poll, nothing fires
            var cleaned = trigger(fake).id(trigger.getId()).status(Property.ofValue(MemberStatusChangeTrigger.Status.CLEANED)).build();
            fake.on("GET", PATH, Response.json(200, page("members", m("c1", "10", "cleaned"))));

            assertThat(cleaned.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
            assertThat(cursor(ctx, "member_status_cleaned", "L1").orElseThrow().idsAtTimestamp(), contains("c1"));
            assertThat(fake.requests().getLast().query(), containsString("sort_dir=DESC"));
        }
    }

    @Test
    void backlogOf2500DrainsAcrossPollsWithEveryRowFiredOnce() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            var rows = new ArrayList<Row>(List.of(new Row("old", B, "unsubscribed")));
            fake.handle("GET", PATH, listServer("members", "last_changed", rows));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            var expected = new ArrayList<String>();
            for (int i = 1; i <= 2500; i++) {
                rows.add(new Row(String.format("r%04d", i), B.plusSeconds(i), "unsubscribed"));
                expected.add(String.format("r%04d", i));
            }

            assertThat(drain(trigger, ctx, 10), equalTo(expected));
        }
    }

    @Test
    void rowMovingWhilePagingIsNotLost() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            var rows = new ArrayList<Row>(List.of(new Row("old", B, "unsubscribed")));
            var server = listServer("members", "last_changed", rows);
            var moved = new AtomicBoolean();
            fake.handle("GET", PATH, request -> {
                var response = server.apply(request);
                if (request.query().contains("since_") && moved.compareAndSet(false, true)) {
                    rows.get(10).time = B.plusSeconds(5000); // r0010 edited again: jumps to the end
                }
                return response;
            });
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            for (int i = 1; i <= 1500; i++) {
                rows.add(new Row(String.format("r%04d", i), B.plusSeconds(i), "unsubscribed"));
            }

            var fired = drain(trigger, ctx, 10);

            for (int i = 1; i <= 1500; i++) {
                var id = String.format("r%04d", i);
                assertThat(id, Collections.frequency(fired, id), is(id.equals("r0010") ? 2 : 1));
            }
        }
    }

    @Test
    void slackWindowWithMoreThanAPageOfKnownRowsStillDrains() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            var rows = new ArrayList<Row>(List.of(new Row("old", B, "unsubscribed")));
            fake.handle("GET", PATH, listServer("members", "last_changed", rows));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            // 1500 rows at second 9 and 1500 at second 10: every poll re-reads > 1000 known rows in the 1 s slack window
            for (int i = 0; i < 3000; i++) {
                rows.add(new Row(String.format("r%04d", i), B.plusSeconds(i < 1500 ? 9 : 10), "unsubscribed"));
            }

            var fired = drain(trigger, ctx, 20);

            assertThat(fired.size(), is(3000));
            assertThat(new HashSet<>(fired).size(), is(3000));
        }
    }
}
