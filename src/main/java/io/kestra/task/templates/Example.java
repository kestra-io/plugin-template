package io.kestra.task.templates;

import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;
import org.kestra.core.models.annotations.Documentation;
import org.kestra.core.models.annotations.InputProperty;
import org.kestra.core.models.annotations.OutputProperty;
import org.kestra.core.models.tasks.RunnableTask;
import org.kestra.core.models.tasks.Task;
import org.kestra.core.runners.RunContext;
import org.slf4j.Logger;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Documentation(
    description = "Short description for this task",
    body = "Full description of this task"
)
public class Example extends Task implements RunnableTask<Example.Output> {
    @InputProperty(
        description = "Short description for this input",
        body = "Full description of this input",
        dynamic = true // If the variables will be rendered with template {{ }}
    )
    private String format;

    @Override
    public Example.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger(this.getClass());

        String render = runContext.render(format);
        logger.debug(render);

        return Output.builder()
            .child(new OutputChild(StringUtils.reverse(render)))
            .build();
    }

    /**
     * Input or Output can nested as you need
     */
    @Builder
    @Getter
    public static class Output implements org.kestra.core.models.tasks.Output {
        @OutputProperty(
            description = "Short description for this output",
            body = "Full description of this output"
        )
        private OutputChild child;
    }

    @Builder
    @Getter
    public static class OutputChild implements org.kestra.core.models.tasks.Output {
        @OutputProperty(
            description = "Short description for this output",
            body = "Full description of this output"
        )
        private String value;
    }
}
