package io.kestra.plugin.mailchimp.audiences;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;
import io.kestra.plugin.mailchimp.MailchimpException;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class UpsertMemberTest {
    // printf 'jane.doe+news@example.com' | md5sum
    private static final String HASH = "6a58e7286ac0e6944c137f0fb3f101b1";
    private static final String PATH = "/3.0/lists/L1/members/" + HASH;
    private static final String OK = "{\"id\":\"" + HASH + "\",\"email_address\":\"jane.doe+news@example.com\",\"status\":\"subscribed\"}";

    @Inject
    private RunContextFactory runContextFactory;

    private UpsertMember.UpsertMemberBuilder<?, ?> task(FakeMailchimpServer fake) {
        return UpsertMember.builder()
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .listId(Property.ofValue("L1"))
            .email(Property.ofValue(" Jane.Doe+news@Example.COM "))
            .statusIfNew(Property.ofValue(UpsertMember.Status.SUBSCRIBED));
    }

    private static JsonNode json(String s) throws Exception {
        return JacksonMapper.ofJson().readTree(s);
    }

    @Test
    void putsNormalizedHashAndFullBody() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("PUT", PATH, Response.json(200, OK));

            var out = task(fake)
                .status(Property.ofValue(UpsertMember.Status.PENDING))
                .mergeFields(Property.ofValue(Map.of("FNAME", "Jane")))
                .tags(Property.ofValue(List.of("a", "b")))
                .language(Property.ofValue("fr"))
                .vip(Property.ofValue(true))
                .skipMergeValidation(Property.ofValue(true))
                .build().run(runContextFactory.of());

            assertThat(fake.requests(), hasSize(1));
            var req = fake.requests().getFirst();
            assertThat(req.method(), equalTo("PUT"));
            assertThat(req.path(), equalTo(PATH));
            assertThat(req.query(), equalTo("skip_merge_validation=true"));
            assertThat(req.header("authorization"), equalTo("Bearer abc-us19"));
            var body = json(req.body());
            assertThat(body.get("email_address").asText(), equalTo("jane.doe+news@example.com"));
            assertThat(body.get("status_if_new").asText(), equalTo("subscribed"));
            assertThat(body.get("status").asText(), equalTo("pending"));
            assertThat(body.at("/merge_fields/FNAME").asText(), equalTo("Jane"));
            assertThat(body.get("tags").toString(), equalTo("[\"a\",\"b\"]"));
            assertThat(body.get("language").asText(), equalTo("fr"));
            assertThat(body.get("vip").asBoolean(), equalTo(true));

            assertThat(out.getId(), equalTo(HASH));
            assertThat(out.getEmailAddress(), equalTo("jane.doe+news@example.com"));
            assertThat(out.getSubscriberHash(), equalTo(HASH));
            assertThat(out.getStatus(), equalTo("subscribed"));
        }
    }

    @Test
    void omitsUnsetOptionals() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("PUT", PATH, Response.json(200, OK));

            task(fake).build().run(runContextFactory.of());

            var req = fake.requests().getFirst();
            assertThat(req.query(), equalTo(null));
            var body = json(req.body());
            assertThat(body.size(), equalTo(2));
            assertThat(body.has("email_address"), equalTo(true));
            assertThat(body.has("status_if_new"), equalTo(true));
        }
    }

    @Test
    void invalidEmailFailsBeforeAnyHttpCall() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            assertThrows(IllegalArgumentException.class,
                () -> task(fake).email(Property.ofValue("no-at-sign")).build().run(runContextFactory.of()));

            assertThat(fake.requests(), hasSize(0));
        }
    }

    @Test
    void badRequestSurfacesFieldMessage() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("PUT", PATH, Response.json(400,
                "{\"title\":\"Invalid Resource\",\"detail\":\"bad\",\"errors\":[{\"field\":\"merge_fields.BIRTHDAY\",\"message\":\"Please enter a valid date\"}]}"));

            var e = assertThrows(MailchimpException.class, () -> task(fake).build().run(runContextFactory.of()));

            assertThat(e.getMessage(), containsString("400 Invalid Resource"));
            assertThat(e.getMessage(), containsString("merge_fields.BIRTHDAY: Please enter a valid date"));
            assertThat(fake.requests(), hasSize(1));
        }
    }

    @Test
    void putIsRetriedOn503() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("PUT", PATH, Response.json(503, "{\"title\":\"Unavailable\"}"), Response.json(200, OK));

            var out = task(fake).maxRetries(Property.ofValue(1)).build().run(runContextFactory.of());

            assertThat(fake.requests(), hasSize(2));
            assertThat(out.getStatus(), equalTo("subscribed"));
        }
    }

    @Test
    void skipMergeValidationFalseSendsNoQueryParam() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("PUT", PATH, Response.json(200, OK));

            task(fake).skipMergeValidation(Property.ofValue(false)).build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().query(), equalTo(null));
        }
    }
}
