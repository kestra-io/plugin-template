package io.kestra.plugin.mailchimp.campaigns;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;
import io.kestra.plugin.mailchimp.MailchimpException;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class SendCampaignTest {
    private static final String CHECKLIST = "/3.0/campaigns/C1/send-checklist";
    private static final String SEND = "/3.0/campaigns/C1/actions/send";

    @Inject
    private RunContextFactory runContextFactory;

    private SendCampaign.SendCampaignBuilder<?, ?> task(FakeMailchimpServer fake) {
        return SendCampaign.builder()
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .campaignId(Property.ofValue("C1"));
    }

    private static long posts(FakeMailchimpServer fake) {
        return fake.requests().stream().filter(r -> r.method().equals("POST")).count();
    }

    @Test
    void unreadyChecklistAbortsWithItemsAndNoPost() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", CHECKLIST, Response.json(200, """
                {"is_ready":false,"items":[
                  {"type":"success","id":1,"heading":"Subject","details":"fine"},
                  {"type":"error","id":2,"heading":"List","details":"Campaign has no recipients"}]}"""));
            fake.on("POST", SEND, Response.empty(204));

            var e = assertThrows(IllegalStateException.class, () -> task(fake).build().run(runContextFactory.of()));

            assertThat(e.getMessage(), containsString("[error] Campaign has no recipients"));
            assertThat(e.getMessage(), containsString("Campaign has no recipients"));
            assertThat(posts(fake), equalTo(0L));
        }
    }

    @Test
    void missingIsReadyIsNotReady() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", CHECKLIST, Response.json(200, "{\"is_ready\":\"yes\",\"items\":[]}"));
            fake.on("POST", SEND, Response.empty(204));

            var e = assertThrows(IllegalStateException.class, () -> task(fake).build().run(runContextFactory.of()));

            assertThat(e.getMessage(), containsString("unreadable or not ready"));
            assertThat(posts(fake), equalTo(0L));
        }
    }

    @Test
    void readyChecklistThenSends() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", CHECKLIST, Response.json(200, "{\"is_ready\":true,\"items\":[]}"));
            fake.on("POST", SEND, Response.empty(204));

            var out = task(fake).build().run(runContextFactory.of());

            assertThat(fake.requests(), hasSize(2));
            assertThat(posts(fake), equalTo(1L));
            assertThat(out.getSent(), equalTo(true));
            assertThat(out.getChecklistReady(), equalTo(true));
            assertThat(out.getCampaignId(), equalTo("C1"));
        }
    }

    @Test
    void requireReadyFalseSkipsChecklist() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", SEND, Response.empty(204));

            var out = task(fake).requireReady(Property.ofValue(false)).build().run(runContextFactory.of());

            assertThat(fake.requests(), hasSize(1));
            assertThat(fake.requests().getFirst().method(), equalTo("POST"));
            assertThat(out.getChecklistReady(), nullValue());
            assertThat(out.getSent(), equalTo(true));
        }
    }

    @Test
    void postIsNotRetriedOn503() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", SEND, Response.json(503, "{\"title\":\"Unavailable\"}"), Response.empty(204));

            assertThrows(MailchimpException.class,
                () -> task(fake).requireReady(Property.ofValue(false)).maxRetries(Property.ofValue(3)).build().run(runContextFactory.of()));

            assertThat(posts(fake), equalTo(1L));
        }
    }

    @Test
    void postIsNotRetriedAfterTimeout() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", SEND, Response.empty(204).delayed(1500), Response.empty(204));

            assertThrows(IOException.class, () -> task(fake)
                .requireReady(Property.ofValue(false))
                .timeout(Property.ofValue(Duration.ofMillis(250)))
                .maxRetries(Property.ofValue(3))
                .build().run(runContextFactory.of()));

            assertThat(posts(fake), equalTo(1L));
        }
    }

    @Test
    void postIsRetriedOn429() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", SEND, Response.json(429, "{\"title\":\"Too Many Requests\"}").withHeader("Retry-After", "0"), Response.empty(204));

            var out = task(fake).requireReady(Property.ofValue(false)).maxRetries(Property.ofValue(1)).build().run(runContextFactory.of());

            assertThat(posts(fake), equalTo(2L));
            assertThat(out.getSent(), equalTo(true));
        }
    }

    @Test
    void alreadySendingSurfacesDetail() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", SEND, Response.json(405, "{\"title\":\"Method Not Allowed\",\"detail\":\"campaign already sending\"}"));

            var e = assertThrows(MailchimpException.class, () -> task(fake).requireReady(Property.ofValue(false)).build().run(runContextFactory.of()));

            assertThat(e.getMessage(), containsString("campaign already sending"));
        }
    }

    @Test
    void invalidCampaignIdFailsBeforeHttp() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            assertThrows(IllegalArgumentException.class, () -> task(fake).campaignId(Property.ofValue(" ")).build().run(runContextFactory.of()));
            assertThrows(IllegalArgumentException.class, () -> task(fake).campaignId(Property.ofValue("a/b")).build().run(runContextFactory.of()));

            assertThat(fake.requests(), hasSize(0));
        }
    }
}
