package io.kestra.plugin.templates;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.triggers.*;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.Optional;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Plugin
@Schema(
    title = "Trigger an execution randomly",
    description ="Trigger an execution randomly"
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {
    @Builder.Default
    private final Duration interval = Duration.ofSeconds(60);

    protected Double min = 0.5;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) {
        double random = Math.random();
        if (random < this.min) {
            return Optional.empty();
        }

        RunContext runContext = conditionContext.getRunContext();
        runContext.logger().info("Will create an execution");
        Execution execution = TriggerService.generateExecution(
            this,
            conditionContext,
            context,
            Output.builder().random(random).build()
        );

        return Optional.of(execution);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        private Double random;
    }
}
