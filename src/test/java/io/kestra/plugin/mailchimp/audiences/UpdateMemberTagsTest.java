package io.kestra.plugin.mailchimp.audiences;

import java.util.List;

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
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class UpdateMemberTagsTest {
    // printf 'jane.doe+news@example.com' | md5sum
    private static final String PATH = "/3.0/lists/L1/members/6a58e7286ac0e6944c137f0fb3f101b1/tags";

    @Inject
    private RunContextFactory runContextFactory;

    private static UpdateMemberTags.Tag tag(String name, String status) {
        return new UpdateMemberTags.Tag(name, status);
    }

    private UpdateMemberTags.UpdateMemberTagsBuilder<?, ?> task(FakeMailchimpServer fake) {
        return UpdateMemberTags.builder()
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .listId(Property.ofValue("L1"))
            .email(Property.ofValue(" Jane.Doe+news@Example.COM "))
            .tags(Property.ofValue(List.of(tag("x", "active"), tag("y", "inactive"))));
    }

    @Test
    void postsTagsAndReturnsApplied() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.empty(204));

            var out = task(fake).build().run(runContextFactory.of());

            var req = fake.requests().getFirst();
            assertThat(req.body(), equalTo("{\"tags\":[{\"name\":\"x\",\"status\":\"active\"},{\"name\":\"y\",\"status\":\"inactive\"}]}"));
            assertThat(out.getApplied(), hasSize(2));
            assertThat(out.getApplied().getFirst().getName(), equalTo("x"));
            assertThat(out.getApplied().getFirst().getStatus(), equalTo("active"));
            assertThat(out.getApplied().getLast().getStatus(), equalTo("inactive"));
        }
    }

    @Test
    void sendsIsSyncingWhenSet() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.empty(204));

            task(fake).isSyncing(Property.ofValue(true)).build().run(runContextFactory.of());

            assertThat(fake.requests().getFirst().body(), containsString("\"is_syncing\":true"));
        }
    }

    @Test
    void emptyTagListFailsBeforeHttp() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            assertThrows(IllegalArgumentException.class, () -> task(fake).tags(Property.ofValue(List.of())).build().run(runContextFactory.of()));

            assertThat(fake.requests(), hasSize(0));
        }
    }

    @Test
    void invalidStatusOrEmailFailsBeforeHttp() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var e = assertThrows(IllegalArgumentException.class,
                () -> task(fake).tags(Property.ofValue(List.of(tag("x", "maybe")))).build().run(runContextFactory.of()));
            assertThat(e.getMessage(), containsString("maybe"));
            assertThrows(IllegalArgumentException.class,
                () -> task(fake).tags(Property.ofValue(List.of(tag(" ", "active")))).build().run(runContextFactory.of()));
            assertThrows(IllegalArgumentException.class,
                () -> task(fake).email(Property.ofValue("nope")).build().run(runContextFactory.of()));

            assertThat(fake.requests(), hasSize(0));
        }
    }

    @Test
    void postIsNotRetriedOn503() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(503, "{\"title\":\"Unavailable\"}"), Response.empty(204));

            assertThrows(MailchimpException.class, () -> task(fake).maxRetries(Property.ofValue(2)).build().run(runContextFactory.of()));

            assertThat(fake.requests(), hasSize(1));
        }
    }

    @Test
    void postIsRetriedOn429() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(429, "{\"title\":\"Too Many Requests\"}"), Response.empty(204));

            task(fake).maxRetries(Property.ofValue(1)).build().run(runContextFactory.of());

            assertThat(fake.requests(), hasSize(2));
        }
    }

    @Test
    void tagsRenderFromFlowStyleListOfMaps() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.empty(204));
            // what the flow YAML provides: plain maps
            var yaml = java.util.Map.<String, Object>of(
                "id", "t", "type", UpdateMemberTags.class.getName(),
                "apiKey", "abc-us19", "baseUrl", fake.baseUrl(), "listId", "L1", "email", "jane.doe+news@example.com",
                "tags", List.of(java.util.Map.of("name", "x", "status", "active")));
            var task = io.kestra.core.serializers.JacksonMapper.ofJson().convertValue(yaml, UpdateMemberTags.class);

            var out = task.run(runContextFactory.of());

            assertThat(out.getApplied(), hasSize(1));
            assertThat(fake.requests().getFirst().body(), equalTo("{\"tags\":[{\"name\":\"x\",\"status\":\"active\"}]}"));
        }
    }
}
