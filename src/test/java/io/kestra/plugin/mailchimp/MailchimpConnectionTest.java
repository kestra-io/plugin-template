package io.kestra.plugin.mailchimp;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.mailchimp.account.Ping;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class MailchimpConnectionTest {
    @Inject
    private RunContextFactory runContextFactory;

    private Ping.PingBuilder<?, ?> task() {
        return Ping.builder();
    }

    @Test
    void apiKeyDerivesServer() throws Exception {
        var task = task().apiKey(Property.ofValue("abc-us19")).build();

        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task), equalTo("https://us19.api.mailchimp.com/3.0"));
    }

    @Test
    void accessTokenNeedsServer() {
        var task = task().accessToken(Property.ofValue("tok")).build();

        var e = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task));
        assertThat(e.getMessage(), containsString("server"));
    }

    @Test
    void apiKeyWithoutDashAndServerSaysSo() {
        var task = task().apiKey(Property.ofValue("nodash")).build();

        var e = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task));
        assertThat(e.getMessage(), containsString("apiKey ending in"));
    }

    @Test
    void accessTokenWithServer() throws Exception {
        var task = task().accessToken(Property.ofValue("tok")).server(Property.ofValue("us6")).build();

        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task), equalTo("https://us6.api.mailchimp.com/3.0"));
    }

    @Test
    void bothOrNeitherCredentialFails() {
        var both = task().apiKey(Property.ofValue("abc-us19")).accessToken(Property.ofValue("tok")).build();
        var neither = task().build();

        assertThat(assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), both)).getMessage(), containsString("exactly one"));
        assertThat(assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), neither)).getMessage(), containsString("exactly one"));
    }

    @Test
    void maliciousServerRejectedBeforeAnyRequest() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            var task = task().apiKey(Property.ofValue("abc-us19")).server(Property.ofValue("evil.com/x#")).baseUrl(Property.ofValue(fake.baseUrl())).build();

            var e = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), task));
            assertThat(e.getMessage(), containsString("server"));
            assertThat(fake.requests(), empty());
        }
    }

    @Test
    void baseUrlMustBeHttpsOrLocalhost() throws Exception {
        var bad = task().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue("http://example.com")).build();
        var good = task().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue("http://localhost:1234")).build();

        assertThat(assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.baseUrl(runContextFactory.of(), bad)).getMessage(), containsString("baseUrl"));
        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), good), equalTo("http://localhost:1234"));
    }

    @Test
    void toStringDoesNotLeakSecrets() {
        var task = task().apiKey(Property.ofValue("abc-supersecret-us19")).accessToken(Property.ofValue("tok-supersecret")).build();

        assertThat(task.toString(), not(containsString("supersecret")));
    }

    @Test
    void sendsBearerAuthorization() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/ping", FakeMailchimpServer.Response.json(200, "{\"health_status\":\"ok\"}"));
            var task = task().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl())).build();

            try (var client = MailchimpConnectionInterface.client(runContextFactory.of(), task)) {
                client.send("GET", "/ping", null, null);
            }

            assertThat(fake.requests().getFirst().header("authorization"), equalTo("Bearer abc-us19"));
        }
    }

    @Test
    void crossHostRedirectIsNotFollowed() throws Exception {
        try (var fake = new FakeMailchimpServer(); var other = new FakeMailchimpServer()) {
            other.on("GET", "/3.0/ping", FakeMailchimpServer.Response.json(200, "{}"));
            fake.on("GET", "/3.0/ping", FakeMailchimpServer.Response.redirect(other.baseUrl() + "/ping"));
            var task = task().apiKey(Property.ofValue("abc-us19")).baseUrl(Property.ofValue(fake.baseUrl())).build();

            try (var client = MailchimpConnectionInterface.client(runContextFactory.of(), task)) {
                var e = assertThrows(MailchimpException.class, () -> client.send("GET", "/ping", null, null));
                assertThat(e.getStatus(), equalTo(302));
            }

            assertThat(other.requests(), empty());
        }
    }

    @Test
    void trailingWhitespaceOnApiKeyIsTrimmed() throws Exception {
        for (var suffix : new String[]{"\n", "\r\n", " "}) {
            try (var fake = new FakeMailchimpServer()) {
                fake.on("GET", "/3.0/ping", FakeMailchimpServer.Response.json(200, "{}"));
                var task = task().apiKey(Property.ofValue("abc-us19" + suffix)).baseUrl(Property.ofValue(fake.baseUrl())).build();

                MailchimpConnectionInterface.client(runContextFactory.of(), task).send("GET", "/ping", null, null);

                assertThat(fake.requests().getFirst().header("authorization"), equalTo("Bearer abc-us19"));
            }
        }
    }

    @Test
    void trailingNewlineOnAccessTokenAndServerIsTrimmed() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/ping", FakeMailchimpServer.Response.json(200, "{}"));
            var task = task().accessToken(Property.ofValue("tok\r\n")).server(Property.ofValue("us6\n")).baseUrl(Property.ofValue(fake.baseUrl() + "\n")).build();

            MailchimpConnectionInterface.client(runContextFactory.of(), task).send("GET", "/ping", null, null);

            assertThat(fake.requests().getFirst().header("authorization"), equalTo("Bearer tok"));
        }
        var noBase = task().accessToken(Property.ofValue("tok")).server(Property.ofValue("us6\n")).build();
        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), noBase), equalTo("https://us6.api.mailchimp.com/3.0"));
    }

    @Test
    void apiKeyWithTrailingNewlineDerivesServer() throws Exception {
        var task = task().apiKey(Property.ofValue("abc-us19\n")).build();

        assertThat(MailchimpConnectionInterface.baseUrl(runContextFactory.of(), task), equalTo("https://us19.api.mailchimp.com/3.0"));
    }

    @Test
    void embeddedNewlineFailsWithoutLeakingTheSecret() {
        var apiKey = task().apiKey(Property.ofValue("SECRETVALUE\nrest-us19")).build();
        var token = task().accessToken(Property.ofValue("SECRETVALUE\nrest")).server(Property.ofValue("us6")).build();

        var e1 = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), apiKey));
        var e2 = assertThrows(IllegalArgumentException.class, () -> MailchimpConnectionInterface.client(runContextFactory.of(), token));
        assertThat(e1.getMessage(), containsString("apiKey"));
        assertThat(e1.getMessage(), not(containsString("SECRETVALUE")));
        assertThat(e2.getMessage(), containsString("accessToken"));
        assertThat(e2.getMessage(), not(containsString("SECRETVALUE")));
    }

    @Test
    void clientNeverLeaksTokenInHeaderError() {
        var client = new MailchimpClient("http://localhost:1", "SECRETVALUE\nrest", 0, java.time.Duration.ofSeconds(1), ms -> { });

        var e = assertThrows(IllegalArgumentException.class, () -> client.send("GET", "/ping", null, null));
        assertThat(e.getMessage(), not(containsString("SECRETVALUE")));
    }
}
