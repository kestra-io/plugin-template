package io.kestra.plugin.mailchimp.integration;

import java.io.BufferedOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.utils.TestsUtils;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.account.Ping;
import io.kestra.plugin.mailchimp.audiences.BatchSubscribe;
import io.kestra.plugin.mailchimp.audiences.ListAudiences;
import io.kestra.plugin.mailchimp.audiences.ListMembers;
import io.kestra.plugin.mailchimp.audiences.NewSubscriberTrigger;
import io.kestra.plugin.mailchimp.audiences.UpdateMemberTags;
import io.kestra.plugin.mailchimp.audiences.UpsertMember;
import io.kestra.plugin.mailchimp.campaigns.ListCampaigns;
import io.kestra.plugin.mailchimp.models.SubscriberHash;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

@EnabledIfEnvironmentVariable(named = "MAILCHIMP_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MAILCHIMP_LIST_ID", matches = ".+")
class CoreIntegrationTest extends MailchimpIntegrationBase {
    private static final String MAILCHIMP_TIME = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{2}:[0-9]{2}";

    private List<Map<String, Object>> listMembers(Instant since) throws Exception {
        return ListMembers.builder().apiKey(apiKeyProperty()).server(serverProperty())
            .listId(Property.ofValue(listId())).sinceLastChanged(Property.ofValue(since))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build().run(runContextFactory.of()).getRows();
    }

    @Test
    void ping() throws Exception {
        var out = Ping.builder().apiKey(apiKeyProperty()).server(serverProperty()).build().run(runContextFactory.of());

        assertThat(out.getHealthStatus(), containsString("Chimpy"));
    }

    @Test
    void pingDerivesServerFromKeySuffix() throws Exception {
        var out = Ping.builder().apiKey(apiKeyProperty()).build().run(runContextFactory.of());

        assertThat(out.getHealthStatus(), containsString("Chimpy"));
    }

    @Test
    void listAudiencesContainsSandboxList() throws Exception {
        var out = ListAudiences.builder().apiKey(apiKeyProperty()).server(serverProperty()).build().run(runContextFactory.of());

        // maxItems unset: every page is fetched, so the sandbox list is found wherever it sits
        assertThat(out.getRows().stream().anyMatch(r -> listId().equals(r.get("id"))), is(true));
    }

    @Test
    void listCampaigns() throws Exception {
        var out = ListCampaigns.builder().apiKey(apiKeyProperty()).server(serverProperty())
            .maxItems(Property.ofValue(5)).build().run(runContextFactory.of());

        assertThat(out.getRows(), notNullValue());
        assertThat(out.getSize(), equalTo((long) out.getRows().size()));
        out.getRows().stream().findFirst().ifPresent(r -> assertThat(r.containsKey("id"), is(true)));
    }

    @Test
    void upsertListAndTagRoundTrip() throws Exception {
        var email = newEmail();
        var tag = "kestra-it-" + UUID.randomUUID().toString().substring(0, 8);
        var since = Instant.now().minus(1, ChronoUnit.HOURS);

        var upserted = UpsertMember.builder().apiKey(apiKeyProperty()).server(serverProperty())
            .listId(Property.ofValue(listId())).email(Property.ofValue(email))
            .statusIfNew(Property.ofValue(UpsertMember.Status.UNSUBSCRIBED))
            .build().run(runContextFactory.of());
        assertThat(upserted.getEmailAddress(), equalTo(email));
        assertThat(upserted.getSubscriberHash(), equalTo(SubscriberHash.of(email)));

        var rows = eventually("member listed after upsert", () -> listMembers(since), r -> r.stream().anyMatch(x -> email.equals(x.get("emailAddress"))));
        var row = rows.stream().filter(r -> email.equals(r.get("emailAddress"))).findFirst().orElseThrow();
        // camelCase keys, ISO-8601 with offset and whole seconds ("2026-01-01T10:00:00+00:00")
        assertThat(String.valueOf(row.get("lastChanged")), matchesPattern(MAILCHIMP_TIME));
        assertThat(String.valueOf(row.get("timestampOpt")), matchesPattern(MAILCHIMP_TIME));
        assertThat(row.get("status"), equalTo("unsubscribed"));

        var tagged = UpdateMemberTags.builder().apiKey(apiKeyProperty()).server(serverProperty())
            .listId(Property.ofValue(listId())).email(Property.ofValue(email))
            .tags(Property.ofValue(List.of(UpdateMemberTags.Tag.builder().name(tag).status("active").build())))
            .build().run(runContextFactory.of());
        assertThat(tagged.getApplied().size(), is(1));

        eventually("tag visible on member", () -> {
            try (var client = client()) {
                return client.send("GET", "/lists/" + MailchimpClient.segment(listId()) + "/members/" + MailchimpClient.segment(SubscriberHash.of(email)), null, null)
                    .path("tags").toString();
            }
        }, tags -> tags.contains(tag));
    }

    @Test
    void batchSubscribeThreeMembers() throws Exception {
        var runContext = runContextFactory.of();
        var since = Instant.now().minus(1, ChronoUnit.HOURS);
        var emails = new ArrayList<String>();
        var file = runContext.workingDir().createTempFile(".ion");
        try (var out = new BufferedOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < 3; i++) {
                var e = newEmail();
                emails.add(e);
                FileSerde.write(out, Map.of("emailAddress", e, "status", "unsubscribed"));
            }
        }
        URI uri = runContext.storage().putFile(file.toFile());

        var out = BatchSubscribe.builder().apiKey(apiKeyProperty()).server(serverProperty())
            .listId(Property.ofValue(listId())).from(Property.ofValue(uri.toString()))
            .build().run(runContext);

        assertThat(out.getErrorCount(), is(0L));
        assertThat(out.getTotalCreated() + out.getTotalUpdated(), is(3L));
        eventually("3 batch members listed", () -> listMembers(since), rows -> emails.stream().allMatch(e -> rows.stream().anyMatch(r -> e.equals(r.get("emailAddress")))));
    }

    @Test
    void newSubscriberTriggerFirstPollFiresNothing() throws Exception {
        var trigger = NewSubscriberTrigger.builder()
            .id("it-" + UUID.randomUUID())
            .type(NewSubscriberTrigger.class.getName())
            .apiKey(apiKeyProperty()).server(serverProperty())
            .listId(Property.ofValue(listId()))
            .build();
        var ctx = TestsUtils.mockTrigger(runContextFactory, trigger);

        assertThat(trigger.evaluate(ctx.getKey(), ctx.getValue()).isEmpty(), is(true));
    }
}
