package io.kestra.plugin.mailchimp.account;

import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.mailchimp.FakeMailchimpServer;
import io.kestra.plugin.mailchimp.FakeMailchimpServer.Response;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@KestraTest
class PingTest {
    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        try (var fake = new FakeMailchimpServer()) {
            fake.on("GET", "/3.0/ping", Response.json(200, "{\"health_status\":\"Everything's Chimpy!\"}"));
            var task = Ping.builder()
                .apiKey(Property.ofValue("abc-us19"))
                .baseUrl(Property.ofValue(fake.baseUrl()))
                .build();

            var output = task.run(runContextFactory.of());

            assertThat(output.getHealthStatus(), equalTo("Everything's Chimpy!"));
        }
    }
}
