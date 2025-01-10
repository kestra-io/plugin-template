package io.kestra.plugin.langchain;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "LangChain4j Text Completion Task",
    description = "Generates text completion using LangChain4j"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Text Completion Example",
            code = {
                "prompt: \"What is the capital of France?\"",
                "model: \"gpt-4\""
            }
        )
    }
)
public class OpenAITextCompletion extends Task implements RunnableTask<OpenAITextCompletion.Output> {
    @Schema(
        title = "Text prompt",
        description = "The input prompt for the language model"
    )
    @NotNull
    private Property<String> prompt;

    @Schema(
        title = "Apikey",
        description = "OpenAI api key"
    )
    @NotNull
    private Property<String> apikey;

    @Schema(
        title = "OpenAi model",
        description = "OpenAI model name"
    )
    @NotNull
    private Property<OpenAiChatModelName> openAiChatModelName = Property.of(OpenAiChatModelName.GPT_4_O_MINI);


    @Override
    public OpenAITextCompletion.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        // Render the input prompt & apikey & model name
        String renderedPrompt = runContext.render(prompt).as(String.class)
            .orElseThrow();
        String renderedApiKey = runContext.render(apikey).as(String.class)
            .orElseThrow();
        OpenAiChatModelName renderedOpenAiChatModelName = runContext.render(openAiChatModelName).as(OpenAiChatModelName.class)
            .orElseThrow();

        logger.info("Prompt: {}", renderedPrompt);
        ChatLanguageModel model = OpenAiChatModel.builder()
            .apiKey(renderedApiKey)
            .modelName(renderedOpenAiChatModelName)
            .build();

        // Generate text completion
        String answer = model.generate(renderedPrompt);
        logger.info("Generated Completion: {}", answer);

        return Output.builder()
            .completion(answer)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Generated text completion",
            description = "The result of the text completion"
        )
        private final String completion;
    }
}
