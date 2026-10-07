package io.kestra.plugin.mailchimp.integration;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.sun.net.httpserver.HttpServer;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.mailchimp.MailchimpException;
import io.kestra.plugin.mailchimp.account.Ping;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks error mapping against the real API with Mailchimp's {@code X-Trigger-Error} header. The tasks cannot set
 * custom headers (and must not grow a property for tests), so a throwaway local proxy adds the header and forwards
 * the call to the real {@code /ping} endpoint; the task under test is pointed at the proxy.
 */
@EnabledIfEnvironmentVariable(named = "MAILCHIMP_API_KEY", matches = ".+")
class ErrorMappingIntegrationTest extends MailchimpIntegrationBase {
    private void assertMapped(String triggerError, int expectedStatus) throws Exception {
        var upstream = HttpClient.newHttpClient();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var req = HttpRequest.newBuilder(URI.create("https://" + server() + ".api.mailchimp.com" + exchange.getRequestURI()))
                .header("Authorization", exchange.getRequestHeaders().getFirst("Authorization"))
                .header("X-Trigger-Error", triggerError)
                .GET().build();
            try {
                var res = upstream.send(req, HttpResponse.BodyHandlers.ofString());
                var body = res.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(res.statusCode(), body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    exchange.getResponseBody().write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var task = Ping.builder().apiKey(apiKeyProperty()).server(serverProperty())
                .baseUrl(Property.ofValue("http://127.0.0.1:" + server.getAddress().getPort() + "/3.0"))
                .maxRetries(Property.ofValue(0))
                .build();

            var e = assertThrows(MailchimpException.class, () -> task.run(runContextFactory.of()));

            assertThat(e.getStatus(), equalTo(expectedStatus));
        } finally {
            server.stop(0);
            upstream.close();
        }
    }

    @Test
    void badRequest() throws Exception {
        assertMapped("BadRequest", 400);
    }

    @Test
    void invalidKey() throws Exception {
        assertMapped("APIKeyInvalid", 401);
    }

    @Test
    void forbidden() throws Exception {
        assertMapped("Forbidden", 403);
    }

    @Test
    void notFound() throws Exception {
        assertMapped("ResourceNotFound", 404);
    }
}
