package io.kestra.plugin.mailchimp.models;

import java.time.Instant;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

class TriggerCursorTest {
    private static final Instant T = Instant.parse("2026-01-01T10:00:00Z");

    @Test
    void roundTrip() {
        var cursor = new TriggerCursor(T, new TreeSet<>(List.of("a|b", "c\"d")));

        assertThat(TriggerCursor.tryParse(cursor.format()).orElseThrow(), is(cursor));
    }

    @Test
    void corruptedValueGivesEmpty() {
        assertThat(TriggerCursor.tryParse("not a cursor").isPresent(), is(false));
        assertThat(TriggerCursor.tryParse("{\"timestamp\":\"nope\",\"ids\":[]}").isPresent(), is(false));
        assertThat(TriggerCursor.tryParse(null).isPresent(), is(false));
    }

    @Test
    void identicalTimestampItemsAreBothNewThenNeitherAfterAdvance() {
        var cursor = new TriggerCursor(T.minusSeconds(60), new TreeSet<>());

        assertThat(cursor.isNew(T, "a"), is(true));
        assertThat(cursor.isNew(T, "b"), is(true));

        var next = cursor.advance(List.of(new TriggerCursor.Item(T, "a"), new TriggerCursor.Item(T, "b")));

        assertThat(next.timestamp(), is(T));
        assertThat(next.idsAtTimestamp(), contains("a", "b"));
        assertThat(next.isNew(T, "a"), is(false));
        assertThat(next.isNew(T, "b"), is(false));
        assertThat(next.isNew(T, "c"), is(true));
    }

    @Test
    void advanceAtSameTimestampKeepsPreviousIds() {
        var cursor = new TriggerCursor(T, new TreeSet<>(List.of("a")));

        var next = cursor.advance(List.of(new TriggerCursor.Item(T, "b")));

        assertThat(next.idsAtTimestamp(), contains("a", "b"));
    }

    @Test
    void olderItemIsNotNew() {
        var cursor = new TriggerCursor(T, new TreeSet<>(List.of("a")));

        assertThat(cursor.isNew(T.minusSeconds(1), "z"), is(false));
        assertThat(cursor.isNew(T.plusSeconds(1), "a"), is(true));
    }
}
