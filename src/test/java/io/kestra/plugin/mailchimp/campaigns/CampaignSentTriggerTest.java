package io.kestra.plugin.mailchimp.campaigns;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;

import jakarta.inject.Inject;

import static io.kestra.plugin.mailchimp.TriggerSupport.cursor;
import static io.kestra.plugin.mailchimp.TriggerSupport.ids;
import static io.kestra.plugin.mailchimp.TriggerSupport.page;
import static io.kestra.plugin.mailchimp.TriggerSupport.vars;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class CampaignSentTriggerTest {
    private static final String PATH = "/3.0/campaigns";

    @Inject
    private RunContextFactory runContextFactory;

    private static String c(String id, String second, String title) {
        return "{\"id\":\"" + id + "\",\"status\":\"sent\",\"send_time\":\"2026-01-01T10:00:" + second + "+00:00\","
            + "\"recipients\":{\"list_id\":\"L1\"},\"settings\":{\"title\":\"" + title + "\"}}";
    }

    private CampaignSentTrigger.CampaignSentTriggerBuilder<?, ?> trigger(FakeMailchimpServer fake) {
        return CampaignSentTrigger.builder()
            .id("sent-" + UUID.randomUUID())
            .type(CampaignSentTrigger.class.getName())
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .maxRetries(Property.ofValue(0));
    }

    @Test
    void firesNewSentCampaignsWithLatestDetails() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).listId(Property.ofValue("L1")).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            fake.on("GET", PATH, Response.json(200, page("campaigns", c("c1", "05", "Old"))));
            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
            assertThat(cursor(ctx, "campaign_sent", "campaign_L1").isPresent(), is(true));

            // boundary row c1 returned again (inclusive server) must not refire
            fake.on("GET", PATH, Response.json(200, page("campaigns", c("c1", "05", "Old"), c("c2", "10", "A"), c("c3", "20", "B"))));
            var execution = trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow();

            var vars = vars(execution);
            assertThat(((Number) vars.get("count")).intValue(), is(2));
            assertThat(ids(ctx, execution), contains("c2", "c3"));
            assertThat(vars, hasEntry("campaignId", "c3"));
            assertThat(vars, hasEntry("sendTime", "2026-01-01T10:00:20+00:00"));
            assertThat(vars, hasEntry("title", "B"));
            assertThat(vars, not(hasKey("report")));
            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));

            assertThat(fake.requests().get(0).query(), equalTo("status=sent&list_id=L1&sort_field=send_time&sort_dir=DESC&count=1000&offset=0"));
            assertThat(fake.requests().get(1).query(), equalTo(
                "status=sent&list_id=L1&since_send_time=2026-01-01T10%3A00%3A04%2B00%3A00"
                    + "&sort_field=send_time&sort_dir=ASC&count=1000&offset=0"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void includeReportAddsReportOfLatest() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).includeReport(Property.ofValue(true)).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            fake.on("GET", PATH, Response.json(200, page("campaigns", c("c1", "05", "Old"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());

            fake.on("GET", PATH, Response.json(200, page("campaigns", c("c2", "10", "A"))));
            fake.on("GET", "/3.0/reports/c2", Response.json(200, "{\"id\":\"c2\",\"emails_sent\":100,\"opens\":{\"opens_total\":5}}"));
            var execution = trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow();

            var report = (Map<String, Object>) vars(execution).get("report");
            assertThat(report, hasEntry("emailsSent", 100));
            assertThat(report, hasKey("opens"));
            assertThat(fake.requests().get(1).query().contains("list_id"), is(false));
        }
    }

    @Test
    void failedReportDoesNotAdvanceCursor() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).includeReport(Property.ofValue(true)).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            fake.on("GET", PATH, Response.json(200, page("campaigns", c("c1", "05", "Old"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());
            var stored = cursor(ctx, "campaign_sent", "campaign_all").orElseThrow();

            fake.on("GET", PATH, Response.json(200, page("campaigns", c("c2", "10", "A"))));
            fake.on("GET", "/3.0/reports/c2", Response.json(503, "{}"));
            assertThrows(Exception.class, () -> trigger.evaluate(ctx.getKey(), ctx.getValue()));

            assertThat(cursor(ctx, "campaign_sent", "campaign_all").orElseThrow(), equalTo(stored));
        }
    }

    @Test
    void reportNotFoundGivesNullReportAndStillAdvances() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).includeReport(Property.ofValue(true)).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);
            fake.on("GET", PATH, Response.json(200, page("campaigns", c("c1", "05", "Old"))));
            trigger.evaluate(ctx.getKey(), ctx.getValue());

            fake.on("GET", PATH, Response.json(200, page("campaigns", c("c2", "10", "A"))));
            fake.on("GET", "/3.0/reports/c2", Response.json(404, "{\"title\":\"Resource Not Found\"}"));
            var execution = trigger.evaluate(ctx.getKey(), ctx.getValue()).orElseThrow();

            assertThat(vars(execution), hasEntry("campaignId", "c2"));
            assertThat(vars(execution).get("report"), nullValue());
            assertThat(cursor(ctx, "campaign_sent", "campaign_all").orElseThrow().idsAtTimestamp(), contains("c2"));
            assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isPresent(), is(false));
        }
    }

    @Test
    void intervalBelowThirtySecondsThrows() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var trigger = trigger(fake).interval(Duration.ofSeconds(1)).build();
            var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

            assertThrows(IllegalArgumentException.class, () -> trigger.evaluate(ctx.getKey(), ctx.getValue()));
        }
    }
}
