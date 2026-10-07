package io.kestra.plugin.mailchimp.audiences;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;
import io.kestra.plugin.mailchimp.ReadTaskSupport;
import io.kestra.plugin.mailchimp.models.SortDir;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class ListMembersTest {
    private static final String PATH = "/3.0/lists/L1/members";
    private static final String BODY = """
        {"members":[
          {"id":"m1","email_address":"a@example.com","status":"subscribed",
           "merge_fields":{"FNAME":"Ann","LNAME":"Lee"},"tags":[{"id":1,"name":"vip"}],
           "timestamp_opt":"2024-01-02T03:04:05+00:00","last_changed":"2024-02-03T00:00:00+00:00",
           "language":"en","vip":true,"ip_signup":"1.2.3.4"},
          {"id":"m2","email_address":"b@example.com","status":"subscribed"}
        ],"total_items":2}
        """;

    @Inject
    private RunContextFactory runContextFactory;

    private ListMembers.ListMembersBuilder<?, ?> task(FakeMailchimpServer fake) {
        return ListMembers.builder()
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .listId(Property.ofValue("L1"));
    }

    @Test
    void fetchMapsRows() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of());

            assertThat(out.getRows(), hasSize(2));
            var row = out.getRows().getFirst();
            assertThat(row, hasEntry("id", "m1"));
            assertThat(row, hasEntry("emailAddress", "a@example.com"));
            assertThat(row, hasEntry("status", "subscribed"));
            assertThat(row, hasEntry("timestampOpt", "2024-01-02T03:04:05+00:00"));
            assertThat(row, hasEntry("lastChanged", "2024-02-03T00:00:00+00:00"));
            assertThat(row, hasEntry("language", "en"));
            assertThat(row, hasEntry("vip", true));
            assertThat(row, hasEntry("ip_signup", "1.2.3.4"));
            assertThat(row.get("tags"), equalTo(List.of(java.util.Map.of("id", 1, "name", "vip"))));
            // nested merge field keys are left untouched
            assertThat(row.get("mergeFields"), equalTo(java.util.Map.of("FNAME", "Ann", "LNAME", "Lee")));
            assertThat(out.getTotal(), is(2L));
            assertThat(fake.requests().getFirst().query(), equalTo("count=1000&offset=0"));
        }
    }

    @Test
    void fetchOne() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build().run(runContextFactory.of());

            assertThat(out.getRow(), hasEntry("id", "m1"));
            assertThat(out.getRows(), nullValue());
        }
    }

    @Test
    void fetchOneOnEmptyAudienceReturnsEmptyOutput() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, "{\"members\":[],\"total_items\":0}"));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build().run(runContextFactory.of());

            assertThat(out.getRow(), nullValue());
            assertThat(out.getRows(), nullValue());
            assertThat(out.getSize(), is(0L));
        }
    }

    @Test
    void none() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.NONE)).build().run(runContextFactory.of());

            assertThat(out.getRows(), nullValue());
            assertThat(out.getUri(), nullValue());
            assertThat(out.getSize(), is(2L));
        }
    }

    @Test
    void storeWritesIonReadableBackToTheSameRows() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));
            var rc = runContextFactory.of();
            var fetched = task(fake).fetchType(Property.ofValue(FetchType.FETCH)).build().run(rc).getRows();

            var out = task(fake).fetchType(Property.ofValue(FetchType.STORE)).build().run(rc);

            assertThat(out.getRows(), nullValue());
            assertThat(ReadTaskSupport.readIon(rc, out.getUri()), equalTo(fetched));
        }
    }

    @Test
    void sendsExactQueryWithSnakeCaseParamsIsoDatesAndLowercaseEnums() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            task(fake)
                .status(Property.ofValue(ListMembers.Status.UNSUBSCRIBED))
                .sinceLastChanged(Property.ofValue(Instant.parse("2024-01-01T00:00:00Z")))
                .sinceTimestampOpt(Property.ofValue(Instant.parse("2024-03-04T05:06:07Z")))
                .sortField(Property.ofValue(ListMembers.SortField.TIMESTAMP_OPT))
                .sortDir(Property.ofValue(SortDir.DESC))
                .fields(Property.ofValue(List.of("members.id", "total_items")))
                .build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().query(), equalTo(
                "status=unsubscribed"
                    + "&since_last_changed=2024-01-01T00%3A00%3A00%2B00%3A00"
                    + "&since_timestamp_opt=2024-03-04T05%3A06%3A07%2B00%3A00"
                    + "&sort_field=timestamp_opt&sort_dir=DESC"
                    + "&fields=members.id%2Ctotal_items"
                    + "&count=1000&offset=0"));
        }
    }

    @Test
    void maxItemsCapsRowsAndDoesNotRequestExtraPages() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH,
                Response.json(200, ReadTaskSupport.page("members", 0, 1000, 5000)),
                Response.json(200, ReadTaskSupport.page("members", 1000, 1000, 5000)),
                Response.json(200, ReadTaskSupport.page("members", 2000, 1000, 5000)));

            var out = task(fake).maxItems(Property.ofValue(1500)).build().run(runContextFactory.of());

            assertThat(out.getRows(), hasSize(1500));
            assertThat(out.getSize(), is(1500L));
            assertThat(out.getTotal(), is(5000L));
            assertThat(fake.requests(), hasSize(2));
        }
    }

    @Test
    void maxItemsBelowPageSizeShrinksTheRequest() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, ReadTaskSupport.page("members", 0, 10, 5000)));

            var out = task(fake).maxItems(Property.ofValue(10)).build().run(runContextFactory.of());

            assertThat(out.getRows(), hasSize(10));
            assertThat(fake.requests(), hasSize(1));
            assertThat(fake.requests().getFirst().query(), equalTo("count=10&offset=0"));
        }
    }

    @Test
    void invalidMaxItemsFailsBeforeAnyRequest() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            assertThrows(IllegalArgumentException.class, () -> task(fake).maxItems(Property.ofValue(0)).build().run(runContextFactory.of()));
            assertThat(fake.requests(), hasSize(0));
        }
    }

    @Test
    void storesTwentyFiveHundredMembersAcrossThreePages() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH,
                Response.json(200, ReadTaskSupport.page("members", 0, 1000, 2500)),
                Response.json(200, ReadTaskSupport.page("members", 1000, 1000, 2500)),
                Response.json(200, ReadTaskSupport.page("members", 2000, 500, 2500)));
            var rc = runContextFactory.of();

            var out = task(fake).fetchType(Property.ofValue(FetchType.STORE)).build().run(rc);

            var rows = ReadTaskSupport.readIon(rc, out.getUri());
            assertThat(rows, hasSize(2500));
            assertThat(rows.getFirst(), hasEntry("id", "i0"));
            assertThat(rows.getLast(), hasEntry("id", "i2499"));
            assertThat(out.getSize(), is(2500L));
            assertThat(fake.requests().stream().map(r -> r.query()).toList(), contains(
                "count=1000&offset=0", "count=1000&offset=1000", "count=1000&offset=2000"));
        }
    }

    @Test
    void segmentUnsafeListIdFailsBeforeAnyRequest() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var e = assertThrows(IllegalArgumentException.class,
                () -> task(fake).listId(Property.ofValue("a/b")).build().run(runContextFactory.of()));

            assertThat(e.getMessage(), containsString("a/b"));
            assertThat(fake.requests(), hasSize(0));
        }
    }

    @Test
    void listIdIsPercentEncodedInThePath() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/lists/a%20b/members", Response.json(200, "{\"members\":[],\"total_items\":0}"));

            task(fake).listId(Property.ofValue("a b")).build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().path(), equalTo("/3.0/lists/a%20b/members"));
        }
    }

    @Test
    void enumsAreLowercasedIndependentlyOfTheDefaultLocale() throws Exception {
        var previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            // with the Turkish default locale, "UNSUBSCRIBED".toLowerCase() would contain a dotless i
            task(fake).status(Property.ofValue(ListMembers.Status.UNSUBSCRIBED)).sortField(Property.ofValue(ListMembers.SortField.TIMESTAMP_OPT))
                .build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().query(), equalTo("status=unsubscribed&sort_field=timestamp_opt&count=1000&offset=0"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }

    @Test
    void fetchOneWithMaxItemsStillReadsOneRow() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, ReadTaskSupport.page("members", 0, 1, 5000)));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH_ONE)).maxItems(Property.ofValue(50)).build().run(runContextFactory.of());

            assertThat(out.getRow(), hasEntry("id", "i0"));
            assertThat(fake.requests(), hasSize(1));
            assertThat(fake.requests().getFirst().query(), equalTo("count=1&offset=0"));
        }
    }

    @Test
    void errorMidPaginationInStoreModePropagates() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH,
                Response.json(200, ReadTaskSupport.page("members", 0, 1000, 3000)),
                Response.json(403, "{\"title\":\"Forbidden\",\"detail\":\"plan limit\"}"));

            var e = assertThrows(io.kestra.plugin.mailchimp.MailchimpException.class,
                () -> task(fake).fetchType(Property.ofValue(FetchType.STORE)).maxRetries(Property.ofValue(0)).build().run(runContextFactory.of()));

            assertThat(e.getMessage(), containsString("403"));
            assertThat(fake.requests(), hasSize(2));
        }
    }
}
