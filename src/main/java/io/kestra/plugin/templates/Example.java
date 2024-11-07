package io.kestra.plugin.templates;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.apache.commons.lang3.StringUtils;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import org.slf4j.Logger;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Short description for this task",
    description = "Full description of this task"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Simple revert",
            code = { "format: \"Text to be reverted\"" }
        )
    }
)
public class Example extends Task implements RunnableTask<Example.Output> {
    @Schema(
        title = "Short description for this input",
        description = "Full description of this input"
    )
    private Property<String> format;

    @Override
    public Example.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String render = runContext.render(format).as(String.class).orElse("");
        logger.debug(render);

        return Output.builder()
            .child(new OutputChild(StringUtils.reverse(render)))
            .build();
    }

    /**
     * Input or Output can be nested as you need
     */
    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Short description for this output",
            description = "Full description of this output"
        )
        private final OutputChild child;
    }

    @Builder
    @Getter
    public static class OutputChild implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Short description for this output",
            description = "Full description of this output"
        )
        private final String value;
    }
}
