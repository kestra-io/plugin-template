package io.kestra.plugin.mailchimp.integration;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.MailchimpException;
import io.kestra.plugin.mailchimp.account.Ping;
import io.kestra.plugin.mailchimp.audiences.ListMembers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Error mapping against the real API, with read-only requests that fail naturally (Mailchimp ignores the
 * {@code X-Trigger-Error} header on {@code /ping} and {@code /lists}, so it cannot be used). A 403 cannot be
 * produced without a restricted key and is only covered by the unit tests.
 */
@EnabledIfEnvironmentVariable(named = "MAILCHIMP_API_KEY", matches = ".+")
class ErrorMappingIntegrationTest extends MailchimpIntegrationBase {
    @Test
    void invalidKeyIs401() {
        var task = Ping.builder().apiKey(Property.ofValue("0".repeat(32) + "-" + server())).server(serverProperty())
            .maxRetries(Property.ofValue(0)).build();

        var e = assertThrows(MailchimpException.class, () -> task.run(runContextFactory.of()));

        assertThat(e.getStatus(), equalTo(401));
    }

    @Test
    void unknownAudienceIs404() {
        var task = ListMembers.builder().apiKey(apiKeyProperty()).server(serverProperty())
            .listId(Property.ofValue("0000000000")).maxRetries(Property.ofValue(0)).build();

        var e = assertThrows(MailchimpException.class, () -> task.run(runContextFactory.of()));

        assertThat(e.getStatus(), equalTo(404));
    }

    @Test
    void malformedSinceFilterIs400() throws Exception {
        try (var client = client()) {
            var e = assertThrows(MailchimpException.class, () -> client.send("GET",
                "/lists/" + MailchimpClient.segment(listId()) + "/members", Map.of("since_last_changed", "garbage", "count", "1"), null));

            assertThat(e.getStatus(), equalTo(400));
        }
    }
}
