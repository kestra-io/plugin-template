package io.kestra.plugin.mailchimp;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Integration tests run against a real account: they must never be able to send a campaign. */
class IntegrationSourcesGuardTest {
    @Test
    void integrationTestsNeverReferenceTheSendAction() throws Exception {
        var dir = Path.of("src/test/java/io/kestra/plugin/mailchimp/integration");
        try (var files = Files.walk(dir)) {
            var sources = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertTrue(!sources.isEmpty(), "no integration sources scanned in " + dir.toAbsolutePath());
            for (var source : sources) {
                var text = Files.readString(source);
                // separate checks so that a concatenated "actions" + "/send" is caught as well
                for (var forbidden : new String[] {"SendCampaign", "actions/", "\"actions", "/send"}) {
                    assertFalse(text.contains(forbidden), source + " must not contain '" + forbidden + "'");
                }
            }
        }
    }
}
