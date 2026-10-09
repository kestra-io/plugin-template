package io.kestra.plugin.mailchimp.reports;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class GetCampaignReportTest {
    @Inject
    private RunContextFactory runContextFactory;

    private GetCampaignReport.GetCampaignReportBuilder<?, ?> task(FakeMailchimpServer fake) {
        return GetCampaignReport.builder()
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .campaignId(Property.ofValue("c1"));
    }

    @Test
    void returnsRowWithCamelCaseSummaryFields() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/reports/c1", Response.json(200, """
                {"id":"c1","emails_sent":100,"unsubscribed":2,
                 "opens":{"opens_total":50,"open_rate":0.5},"clicks":{"clicks_total":10},
                 "bounces":{"hard_bounces":1},"campaign_title":"May","ecommerce":{"total_orders":3}}
                """));

            var out = task(fake).build().run(runContextFactory.of());

            var row = out.getRow();
            assertThat(row, hasEntry("id", "c1"));
            assertThat(row, hasEntry("emailsSent", 100));
            assertThat(row, hasEntry("unsubscribed", 2));
            assertThat(row.get("opens"), equalTo(java.util.Map.of("opens_total", 50, "open_rate", 0.5)));
            assertThat(row, hasKey("clicks"));
            assertThat(row, hasKey("bounces"));
            assertThat(row, hasEntry("campaign_title", "May"));
            assertThat(fake.requests(), hasSize(1));
            assertThat(fake.requests().getFirst().query(), equalTo(null));
        }
    }

    @Test
    void fieldsTrimTheResponse() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/reports/c1", Response.json(200, "{\"emails_sent\":100,\"clicks\":{\"clicks_total\":10}}"));

            var out = task(fake).fields(Property.ofValue(List.of("emails_sent", "clicks.clicks_total"))).build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().query(), equalTo("fields=emails_sent%2Cclicks.clicks_total"));
            assertThat(out.getRow(), hasEntry("emailsSent", 100));
            assertThat(out.getRow(), not(hasKey("opens")));
        }
    }

    @Test
    void emptyBodyGivesEmptyRow() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/reports/c1", Response.json(200, "{}"));

            assertThat(task(fake).build().run(runContextFactory.of()).getRow(), anEmptyMap());
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
