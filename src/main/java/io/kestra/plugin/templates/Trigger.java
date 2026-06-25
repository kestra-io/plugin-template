package io.kestra.plugin.templates;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
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
@Plugin(
    examples = {
        @Example(
            title = "Trigger an execution randomly",
            full = true,
            code = """
                id: example_trigger
                namespace: company.team

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Triggered"

                triggers:
                  - id: random
                    type: io.kestra.plugin.templates.Trigger
                    min: 0.5
                """
        )
    }
)
@Schema(
    title = "Trigger an execution randomly",
    description = "Trigger an execution randomly"
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {
    @Builder.Default
    private final Duration interval = Duration.ofSeconds(60);

    @Schema(title = "Probability threshold below which no execution is created")
    @Builder.Default
    protected Property<Double> min = Property.ofValue(0.5);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws IllegalVariableEvaluationException {
        RunContext runContext = conditionContext.getRunContext();

        double random = Math.random();
        if (random < runContext.render(this.min).as(Double.class).orElseThrow()) {
            return Optional.empty();
        }

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
        @Schema(title = "The random value that triggered the execution")
        private Double random;
    }
}
