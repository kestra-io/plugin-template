package io.kestra.plugin.mailchimp.integration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Collection;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.function.ThrowingSupplier;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.mailchimp.MailchimpClient;
import io.kestra.plugin.mailchimp.models.SubscriberHash;

import jakarta.inject.Inject;

/**
 * Base of the optional integration tests. Subclasses must carry
 * {@code @EnabledIfEnvironmentVariable(named = "MAILCHIMP_API_KEY", matches = ".+")} (and {@code MAILCHIMP_LIST_ID}
 * when they write to an audience) so that they report as skipped without credentials.
 *
 * <p>Rules: only the audience {@code MAILCHIMP_LIST_ID} is written to, members are
 * {@code kestra-it-<uuid>@<test domain>}, always written with status {@code unsubscribed},, every created member is archived in {@link #cleanup()}, and the API key is
 * never logged.
 */
@KestraTest
abstract class MailchimpIntegrationBase {
    @Inject
    protected RunContextFactory runContextFactory;

    private final List<String> created = new ArrayList<>();

    protected static String apiKey() {
        return System.getenv("MAILCHIMP_API_KEY");
    }

    /** Explicit {@code MAILCHIMP_SERVER}, else the key suffix. */
    protected static String server() {
        var s = System.getenv("MAILCHIMP_SERVER");
        return s != null && !s.isBlank() ? s.trim() : apiKey().substring(apiKey().lastIndexOf('-') + 1);
    }

    protected static String listId() {
        return System.getenv("MAILCHIMP_LIST_ID");
    }

    protected static String campaignId() {
        return System.getenv("MAILCHIMP_CAMPAIGN_ID");
    }

    protected static Property<String> apiKeyProperty() {
        return Property.ofValue(apiKey());
    }

    protected static Property<String> serverProperty() {
        return Property.ofValue(server());
    }

    protected static MailchimpClient client() {
        return new MailchimpClient("https://" + server() + ".api.mailchimp.com/3.0", apiKey(), 3, Duration.ofSeconds(60), Thread::sleep);
    }

    /** Optional {@code MAILCHIMP_TEST_EMAIL_DOMAIN}, default {@code example.com}. */
    protected static String emailDomain() {
        var d = System.getenv("MAILCHIMP_TEST_EMAIL_DOMAIN");
        return d != null && !d.isBlank() ? d.trim() : "example.com";
    }

    /** Polls {@code supplier} until {@code done} accepts the value (Mailchimp is eventually consistent). */
    protected static <T> T eventually(String what, ThrowingSupplier<T> supplier, Predicate<T> done) throws Exception {
        return eventually(Duration.ofSeconds(20), Duration.ofSeconds(1), what, supplier, done);
    }

    protected static <T> T eventually(Duration timeout, Duration interval, String what, ThrowingSupplier<T> supplier, Predicate<T> done) throws Exception {
        var deadline = System.nanoTime() + timeout.toNanos();
        T last;
        while (true) {
            try {
                last = supplier.get();
            } catch (Throwable e) {
                throw e instanceof Exception ex ? ex : new RuntimeException(e);
            }
            if (done.test(last)) {
                return last;
            }
            if (System.nanoTime() >= deadline) {
                // only sizes are reported, never payloads or credentials
                throw new AssertionError("Timed out after " + timeout + " waiting for: " + what
                    + (last instanceof Collection<?> c ? " (last result had " + c.size() + " items)" : ""));
            }
            Thread.sleep(interval.toMillis());
        }
    }

    /** A fresh test address, registered for removal after the test. */
    protected String newEmail() {
        var email = "kestra-it-" + UUID.randomUUID() + "@" + emailDomain();
        created.add(email);
        return email;
    }

    @AfterEach
    void cleanup() {
        if (listId() == null || listId().isBlank()) {
            return;
        }
        try (var client = client()) {
            for (var email : created) {
                try {
                    client.send("DELETE", "/lists/" + MailchimpClient.segment(listId()) + "/members/" + MailchimpClient.segment(SubscriberHash.of(email)), null, null);
                } catch (Exception e) {
                    // member was never created (404) or already archived: nothing to remove
                    System.err.println("integration cleanup: could not archive " + email + ": " + e.getMessage());
                }
            }
        } finally {
            created.clear();
        }
    }
}
