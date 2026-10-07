package io.kestra.plugin.mailchimp.campaigns;

import java.time.Instant;

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
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

@KestraTest
class ListCampaignsTest {
    private static final String PATH = "/3.0/campaigns";
    private static final String BODY = """
        {"campaigns":[
          {"id":"c1","type":"regular","status":"sent","send_time":"2024-05-06T07:08:09+00:00","emails_sent":42,
           "recipients":{"list_id":"L1","list_name":"News"},
           "settings":{"subject_line":"Hello","title":"May news"},"web_id":7},
          {"id":"c2","type":"plaintext","status":"save"}
        ],"total_items":2}
        """;

    @Inject
    private RunContextFactory runContextFactory;

    private ListCampaigns.ListCampaignsBuilder<?, ?> task(FakeMailchimpServer fake) {
        return ListCampaigns.builder().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl()));
    }

    @Test
    void fetchMapsRows() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH)).build().run(runContextFactory.of());

            assertThat(out.getRows(), hasSize(2));
            var row = out.getRows().getFirst();
            assertThat(row, hasEntry("id", "c1"));
            assertThat(row, hasEntry("type", "regular"));
            assertThat(row, hasEntry("status", "sent"));
            assertThat(row, hasEntry("sendTime", "2024-05-06T07:08:09+00:00"));
            assertThat(row, hasEntry("emailsSent", 42));
            assertThat(row, hasEntry("listId", "L1"));
            assertThat(row, hasEntry("subjectLine", "Hello"));
            assertThat(row, hasEntry("title", "May news"));
            assertThat(row, hasEntry("web_id", 7));
            assertThat(row, hasKey("recipients"));
            assertThat(row, hasKey("settings"));
            assertThat(out.getTotal(), is(2L));
            assertThat(fake.requests().getFirst().query(), equalTo("count=1000&offset=0"));
        }
    }

    @Test
    void fetchOne() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            var out = task(fake).fetchType(Property.ofValue(FetchType.FETCH_ONE)).build().run(runContextFactory.of());

            assertThat(out.getRow(), hasEntry("id", "c1"));
            assertThat(out.getRows(), nullValue());
        }
    }

    @Test
    void fetchOneOnEmptyReturnsEmptyOutput() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, "{\"campaigns\":[],\"total_items\":0}"));

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
            assertThat(out.getUri(), nullValue());
            assertThat(out.getSize(), is(2L));
        }
    }

    @Test
    void store() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));
            var rc = runContextFactory.of();
            var fetched = task(fake).build().run(rc).getRows();

            var out = task(fake).fetchType(Property.ofValue(FetchType.STORE)).build().run(rc);

            assertThat(ReadTaskSupport.readIon(rc, out.getUri()), equalTo(fetched));
        }
    }

    @Test
    void sendsExactQuery() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH, Response.json(200, BODY));

            task(fake)
                .campaignType(Property.ofValue(ListCampaigns.Type.REGULAR))
                .status(Property.ofValue(ListCampaigns.Status.SENT))
                .sinceSendTime(Property.ofValue(Instant.parse("2024-01-01T00:00:00Z")))
                .beforeSendTime(Property.ofValue(Instant.parse("2024-06-01T12:00:00Z")))
                .listId(Property.ofValue("L 1"))
                .sortField(Property.ofValue(ListCampaigns.SortField.SEND_TIME))
                .sortDir(Property.ofValue(SortDir.ASC))
                .build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().query(), equalTo(
                "type=regular&status=sent"
                    + "&since_send_time=2024-01-01T00%3A00%3A00%2B00%3A00"
                    + "&before_send_time=2024-06-01T12%3A00%3A00%2B00%3A00"
                    + "&list_id=L+1"
                    + "&sort_field=send_time&sort_dir=ASC"
                    + "&count=1000&offset=0"));
        }
    }

    @Test
    void maxItemsCapsRowsWithoutExtraPages() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", PATH,
                Response.json(200, ReadTaskSupport.page("campaigns", 0, 1000, 3000)),
                Response.json(200, ReadTaskSupport.page("campaigns", 1000, 1000, 3000)),
                Response.json(200, ReadTaskSupport.page("campaigns", 2000, 1000, 3000)));

            var out = task(fake).maxItems(Property.ofValue(1200)).build().run(runContextFactory.of());

            assertThat(out.getRows(), hasSize(1200));
            assertThat(fake.requests().stream().map(r -> r.query()).toList(), contains(
                "count=1000&offset=0", "count=1000&offset=1000"));
        }
    }
}
