package io.kestra.plugin.mailchimp.audiences;

import java.io.BufferedOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.FileSerde;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class BatchSubscribeTest {
    private static final String PATH = "/3.0/lists/L1";

    @Inject
    private RunContextFactory runContextFactory;

    private static Map<String, Object> row(int i) {
        return Map.of("emailAddress", "user" + i + "@example.com", "status", "subscribed");
    }

    private static URI store(RunContext rc, List<Map<String, Object>> rows) throws Exception {
        var file = rc.workingDir().createTempFile(".ion");
        try (var out = new BufferedOutputStream(Files.newOutputStream(file))) {
            for (var r : rows) {
                FileSerde.write(out, r);
            }
        }
        return rc.storage().putFile(file.toFile());
    }

    private static List<Map<String, Object>> rows(int n) {
        return IntStream.range(0, n).mapToObj(BatchSubscribeTest::row).collect(Collectors.toList());
    }

    private BatchSubscribe.BatchSubscribeBuilder<?, ?> task(FakeMailchimpServer fake, URI uri) {
        return BatchSubscribe.builder()
            .apiKey(Property.ofValue("abc-us19"))
            .baseUrl(Property.ofValue(fake.baseUrl()))
            .listId(Property.ofValue("L1"))
            .from(Property.ofValue(uri.toString()));
    }

    private static JsonNode json(String s) throws Exception {
        return JacksonMapper.ofJson().readTree(s);
    }

    private static String ok(int created, int updated) {
        return "{\"total_created\":" + created + ",\"total_updated\":" + updated + ",\"error_count\":0,\"errors\":[]}";
    }

    @Test
    void sendsThreeSequentialChunksAndSumsTotals() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(200, ok(400, 100)), Response.json(200, ok(500, 0)), Response.json(200, ok(150, 50)));
            var rc = runContextFactory.of();

            var out = task(fake, store(rc, rows(1200))).build().run(rc);

            var reqs = fake.requests();
            assertThat(reqs, hasSize(3));
            assertThat(reqs.stream().map(r -> r.method() + " " + r.path()).distinct().toList(), equalTo(List.of("POST " + PATH)));
            var bodies = new ArrayList<JsonNode>();
            for (var r : reqs) {
                bodies.add(json(r.body()));
            }
            assertThat(bodies.stream().map(b -> b.get("members").size()).toList(), equalTo(List.of(500, 500, 200)));
            // order preserved: first member of each chunk
            assertThat(bodies.get(0).at("/members/0/email_address").asText(), equalTo("user0@example.com"));
            assertThat(bodies.get(1).at("/members/0/email_address").asText(), equalTo("user500@example.com"));
            assertThat(bodies.get(2).at("/members/199/email_address").asText(), equalTo("user1199@example.com"));
            assertThat(bodies.get(0).at("/members/0/status").asText(), equalTo("subscribed"));

            assertThat(out.getTotalCreated(), is(1050L));
            assertThat(out.getTotalUpdated(), is(150L));
            assertThat(out.getErrorCount(), is(0L));
            assertThat(out.getErrors(), hasSize(0));
            assertThat(out.getBatches(), is(3));
        }
    }

    @Test
    void mapsRowsAndSendsFlagsInTheBody() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(200, ok(1, 0)));
            var rc = runContextFactory.of();
            var uri = store(rc, List.of(Map.of(
                "emailAddress", " Jane@Example.COM ", "status", "Pending",
                "mergeFields", Map.of("FNAME", "Jane"), "tags", List.of("a", "b"))));

            task(fake, uri)
                .updateExisting(Property.ofValue(true))
                .syncTags(Property.ofValue(false))
                .skipMergeValidation(Property.ofValue(true))
                .skipDuplicateCheck(Property.ofValue(true))
                .build().run(rc);

            var req = fake.requests().getFirst();
            assertThat(req.query(), equalTo(null));
            var body = json(req.body());
            assertThat(body.get("update_existing").asBoolean(), is(true));
            assertThat(body.get("sync_tags").asBoolean(), is(false));
            assertThat(body.get("skip_merge_validation").asBoolean(), is(true));
            assertThat(body.get("skip_duplicate_check").asBoolean(), is(true));
            var m = body.at("/members/0");
            assertThat(m.get("email_address").asText(), equalTo("jane@example.com"));
            assertThat(m.get("status").asText(), equalTo("pending"));
            assertThat(m.at("/merge_fields/FNAME").asText(), equalTo("Jane"));
            assertThat(m.get("tags").toString(), equalTo("[\"a\",\"b\"]"));
        }
    }

    @Test
    void omitsUnsetFlagsAndOptionalFields() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(200, ok(1, 0)));
            var rc = runContextFactory.of();

            task(fake, store(rc, rows(1))).build().run(rc);

            var body = json(fake.requests().getFirst().body());
            assertThat(body.size(), equalTo(1));
            assertThat(body.at("/members/0").size(), equalTo(2));
        }
    }

    @Test
    void okStatusWithErrorCountFailsByDefaultAfterAllChunksAreSent() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH,
                Response.json(200, "{\"total_created\":498,\"total_updated\":0,\"error_count\":2,\"errors\":["
                    + "{\"email_address\":\"bad1@example.com\",\"error\":\"invalid\",\"error_code\":\"ERROR_CONTACT_EXISTS\"},"
                    + "{\"email_address\":\"bad2@example.com\",\"error\":\"nope\",\"error_code\":\"ERROR_X\"}]}"),
                Response.json(200, ok(100, 0)));
            var rc = runContextFactory.of();

            var e = assertThrows(IllegalStateException.class, () -> task(fake, store(rc, rows(600))).build().run(rc));

            assertThat(e.getMessage(), containsString("2"));
            assertThat(e.getMessage(), containsString("bad1@example.com"));
            assertThat(e.getMessage(), containsString("bad2@example.com"));
            assertThat(fake.requests(), hasSize(2)); // the failing chunk did not stop the rest
        }
    }

    @Test
    void failOnErrorFalseReturnsErrorsInOutput() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(200, "{\"total_created\":498,\"total_updated\":0,\"error_count\":2,\"errors\":["
                + "{\"email_address\":\"bad1@example.com\",\"error\":\"invalid\",\"error_code\":\"ERROR_CONTACT_EXISTS\"},"
                + "{\"email_address\":\"bad2@example.com\",\"error\":\"nope\",\"error_code\":\"ERROR_X\"}]}"));
            var rc = runContextFactory.of();

            var out = task(fake, store(rc, rows(500))).failOnError(Property.ofValue(false)).build().run(rc);

            assertThat(out.getErrorCount(), is(2L));
            assertThat(out.getTotalCreated(), is(498L));
            assertThat(out.getErrors(), hasSize(2));
            assertThat(out.getErrors().getFirst().getEmailAddress(), equalTo("bad1@example.com"));
            assertThat(out.getErrors().getFirst().getError(), equalTo("invalid"));
            assertThat(out.getErrors().getFirst().getErrorCode(), equalTo("ERROR_CONTACT_EXISTS"));
        }
    }

    @Test
    void failureMessageCapsListedEmails() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var errors = IntStream.range(0, 15)
                .mapToObj(i -> "{\"email_address\":\"bad" + i + "@example.com\",\"error\":\"x\",\"error_code\":\"E\"}")
                .collect(Collectors.joining(","));
            fake.on("POST", PATH, Response.json(200, "{\"total_created\":0,\"total_updated\":0,\"error_count\":15,\"errors\":[" + errors + "]}"));
            var rc = runContextFactory.of();

            var e = assertThrows(IllegalStateException.class, () -> task(fake, store(rc, rows(15))).build().run(rc));

            assertThat(e.getMessage(), containsString("bad9@example.com"));
            assertThat(e.getMessage(), not(containsString("bad10@example.com")));
            assertThat(e.getMessage(), containsString("and 5 more"));
            assertThat(e.getMessage(), containsString("15"));
        }
    }

    @Test
    void emptyFileMakesNoRequest() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var rc = runContextFactory.of();

            var out = task(fake, store(rc, List.of())).build().run(rc);

            assertThat(fake.requests(), hasSize(0));
            assertThat(out.getTotalCreated(), is(0L));
            assertThat(out.getTotalUpdated(), is(0L));
            assertThat(out.getErrorCount(), is(0L));
            assertThat(out.getErrors(), hasSize(0));
            assertThat(out.getBatches(), is(0));
        }
    }

    @Test
    void missingEmailInALaterChunkFailsBeforeAnyHttpAndNamesTheRow() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var rc = runContextFactory.of();
            var data = rows(1000);
            data.set(699, Map.of("status", "subscribed")); // 1-based row 700, in chunk 2

            var e = assertThrows(IllegalArgumentException.class, () -> task(fake, store(rc, data)).build().run(rc));

            assertThat(e.getMessage(), containsString("row 700"));
            assertThat(fake.requests(), hasSize(0));
        }
    }

    @Test
    void invalidEmailOrStatusFailsBeforeAnyHttp() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var rc = runContextFactory.of();

            var e1 = assertThrows(IllegalArgumentException.class,
                () -> task(fake, store(rc, List.of(row(0), Map.of("emailAddress", "no-at", "status", "subscribed")))).build().run(rc));
            var e2 = assertThrows(IllegalArgumentException.class,
                () -> task(fake, store(rc, List.of(Map.of("emailAddress", "a@b.co", "status", "weird")))).build().run(rc));

            assertThat(e1.getMessage(), containsString("row 2"));
            assertThat(e2.getMessage(), containsString("row 1"));
            assertThat(fake.requests(), hasSize(0));
        }
    }

    @Test
    void serverErrorOnSecondChunkFailsWithoutRetryAndReportsSucceededChunks() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(200, ok(500, 0)), Response.json(503, "{\"title\":\"Unavailable\"}"), Response.json(200, ok(200, 0)));
            var rc = runContextFactory.of();

            var e = assertThrows(IllegalStateException.class, () -> task(fake, store(rc, rows(1200))).maxRetries(Property.ofValue(3)).build().run(rc));

            assertThat(fake.requests(), hasSize(2));
            assertThat(e.getMessage(), containsString("1 of 3 chunk"));
            assertThat(e.getMessage(), containsString("503"));
        }
    }

    @Test
    void rateLimitedChunkIsRetried() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(429, "{\"title\":\"Too Many Requests\"}"), Response.json(200, ok(1, 0)));
            var rc = runContextFactory.of();

            var out = task(fake, store(rc, rows(1))).maxRetries(Property.ofValue(1)).build().run(rc);

            assertThat(fake.requests(), hasSize(2));
            assertThat(out.getTotalCreated(), is(1L));
        }
    }

    @Test
    void invalidChunkSizeFailsBeforeAnyHttp() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var rc = runContextFactory.of();
            var uri = store(rc, rows(3));

            assertThrows(IllegalArgumentException.class, () -> task(fake, uri).chunkSize(Property.ofValue(501)).build().run(rc));
            assertThrows(IllegalArgumentException.class, () -> task(fake, uri).chunkSize(Property.ofValue(0)).build().run(rc));

            assertThat(fake.requests(), hasSize(0));
        }
    }

    @Test
    void customChunkSizeSplitsRows() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(200, ok(2, 0)));
            var rc = runContextFactory.of();

            var out = task(fake, store(rc, rows(5))).chunkSize(Property.ofValue(2)).build().run(rc);

            assertThat(out.getBatches(), is(3));
            assertThat(fake.requests().stream().map(r -> {
                try {
                    return json(r.body()).get("members").size();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }).toList(), equalTo(List.of(2, 2, 1)));
        }
    }

    @Test
    void midRunFailureReportsTotalsAndRejectedEmailsSoFar() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH,
                Response.json(200, "{\"total_created\":497,\"total_updated\":1,\"error_count\":2,\"errors\":["
                    + "{\"email_address\":\"bad1@example.com\",\"error\":\"x\",\"error_code\":\"E1\"},"
                    + "{\"error\":\"y\",\"error_code\":\"E2\"}]}"),
                Response.json(503, "{\"title\":\"Unavailable\"}"));
            var rc = runContextFactory.of();

            var e = assertThrows(IllegalStateException.class, () -> task(fake, store(rc, rows(1000))).build().run(rc));

            assertThat(e.getMessage(), containsString("1 of 2 chunk(s) succeeded"));
            assertThat(e.getMessage(), containsString("resume from row 501"));
            assertThat(e.getMessage(), containsString("497 created, 1 updated, 2 rejected"));
            assertThat(e.getMessage(), containsString("bad1@example.com"));
            assertThat(e.getMessage(), containsString("<unknown>"));
            assertThat(e.getMessage(), not(containsString("null")));
        }
    }

    @Test
    void serverErrorOnFirstChunkReportsZeroSucceeded() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("POST", PATH, Response.json(503, "{\"title\":\"Unavailable\"}"));
            var rc = runContextFactory.of();

            var e = assertThrows(IllegalStateException.class, () -> task(fake, store(rc, rows(600))).build().run(rc));

            assertThat(e.getMessage(), containsString("0 of 2 chunk(s) succeeded"));
            assertThat(e.getMessage(), containsString("resume from row 1"));
            assertThat(fake.requests(), hasSize(1));
        }
    }
}
