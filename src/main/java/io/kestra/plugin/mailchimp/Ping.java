package io.kestra.plugin.mailchimp;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Ping Mailchimp",
    description = "Calls the Mailchimp `GET /ping` endpoint to check that the credentials and data center are valid."
)
@Plugin(
    examples = {
        @Example(
            title = "Check the Mailchimp connection.",
            full = true,
            code = """
                id: mailchimp_ping
                namespace: company.team

                tasks:
                  - id: ping
                    type: io.kestra.plugin.mailchimp.Ping
                    apiKey: "{{ secret('MAILCHIMP_API_KEY') }}"
                """
        )
    }
)
public class Ping extends AbstractMailchimpTask implements RunnableTask<Ping.Output> {

    @Override
    public Output run(RunContext runContext) throws Exception {
        try (var client = client(runContext)) {
            var response = client.send("GET", "/ping", null, null);
            var status = response.path("health_status").asText();
            runContext.logger().info("Mailchimp says: {}", status);
            return Output.builder().healthStatus(status).build();
        }
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(title = "Health status", description = "Value of `health_status` returned by Mailchimp, `Everything's Chimpy!` when healthy.")
        private final String healthStatus;
    }
}
