package io.kestra.plugin.mailchimp.reports;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;
import io.kestra.plugin.mailchimp.ReadTaskSupport;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListEmailActivityTest {
    private static final String PATH = "/3.0/reports/c1/email-activity";
    private static final String BODY = """
        {"emails":[
          {"campaign_id":"c1","list_id":"L1","email_id":"e1","email_address":"a@example.com",
           "activity":[{"action":"open","timestamp":"2024-01-01T00:00:00+00:00","ip":"1.1.1.1"},
                       {"action":"click","timestamp":"2024-01-01T00:01:00+00:00","url":"https://x.test","ip":"2.2.2.2","is_bot":true}]},
          {"campaign_id":"c1","email_id":"e2","email_address":"b@example.com",
           "activity":[{"action":"open","timestamp":"2024-01-02T00:00:00+00:00","is_bot":true}]}
        ],"total_items":2}
        """;

    @Inject
    private RunContextFactory runContextFactory;

    private ListEmailActivity.ListEmailActivityBuilder<?, ?> task(FakeMailchimpServer fake) {
        return ListEmailActivity.builder()
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .campaignId(Property.ofValue("c1"));
    }

    @Test
    void fetchMapsRowsFromEmailsArray() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of());

            assertThat(out.getRows(), hasSize(2));
            var row = out.getRows().getFirst();
            assertThat(row, hasEntry("emailAddress", "a@example.com"));
            assertThat(row, hasEntry("emailId", "e1"));
            assertThat(row, hasEntry("campaign_id", "c1"));
            assertThat(row, hasEntry("list_id", "L1"));
            assertThat((java.util.List<?>) row.get("activity"), hasSize(2));
            assertThat(out.getTotal(), is(2L));
            assertThat(fake.requests().getFirst().query(), equalTo("count=1000&offset=0"));
        }
    }

    @Test
    void fetchOne() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build().run(runContextFactory.of());

            assertThat(out.getRow(), hasEntry("emailId", "e1"));
            assertThat(out.getRows(), nullValue());
        }
    }

    @Test
    void fetchOneOnEmptyReturnsEmptyOutput() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, "{\"emails\":[],\"total_items\":0}"));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build().run(runContextFactory.of());

            assertThat(out.getRow(), nullValue());
            assertThat(out.getSize(), is(0L));
        }
    }

    @Test
    void none() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.NONE)).build().run(runContextFactory.of());

            assertThat(out.getRows(), nullValue());
            assertThat(out.getSize(), is(2L));
        }
    }

    @Test
    void storeRoundtrip() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));
            var rc = runContextFactory.of();
            var fetched = task(fake).build().run(rc).getRows();

            var out = task(fake).fetchType(Property.ofValue(FetchType.STORE)).build().run(rc);

            assertThat(ReadTaskSupport.readIon(rc, out.getUri()), equalTo(fetched));
        }
    }

    @Test
    void sendsSinceAsIso() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            task(fake).since(Property.ofValue(Instant.parse("2024-01-01T00:00:00Z"))).build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().query(), equalTo("since=2024-01-01T00%3A00%3A00%2B00%3A00&count=1000&offset=0"));
        }
    }

    @Test
    void filterBotsTrueSendsTheApiParameter() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).filterBots(Property.ofValue(true)).build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().query(), equalTo("filter_bots=true&count=1000&offset=0"));
            assertThat(out.getRows(), hasSize(2));
        }
    }

    @Test
    void filterBotsFalseOrUnsetOmitsTheParameter() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            task(fake).filterBots(Property.ofValue(false)).build().run(runContextFactory.of());
            task(fake).build().run(runContextFactory.of());

            assertThat(fake.requests().stream().map(r -> r.query()).toList(), contains("count=1000&offset=0", "count=1000&offset=0"));
        }
    }

    @Test
    void maxItemsCapsRowsWithoutExtraPages() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH,
                Response.json(200, ReadTaskSupport.page("emails", 0, 1000, 3000)),
                Response.json(200, ReadTaskSupport.page("emails", 1000, 1000, 3000)));

            var out = task(fake).maxItems(Property.ofValue(1000)).build().run(runContextFactory.of());

            assertThat(out.getRows(), hasSize(1000));
            assertThat(fake.requests(), hasSize(1));
        }
    }

    @Test
    void segmentUnsafeCampaignIdFailsBeforeAnyRequest() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            assertThrows(IllegalArgumentException.class,
                () -> task(fake).campaignId(Property.ofValue("a/b")).build().run(runContextFactory.of()));

            assertThat(fake.requests(), hasSize(0));
        }
    }
}
