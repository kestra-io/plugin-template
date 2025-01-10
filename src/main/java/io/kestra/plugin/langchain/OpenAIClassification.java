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

import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "LangChain4j Classification Task",
    description = "Classifies text using LangChain4j by generating a class label"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Classification Example",
            code = {
                "prompt: \"Is 'This is a joke' a good joke?\"",
                "classes: [\"true\", \"false\"]",
                "model: \"gpt-4\""
            }
        )
    }
)
public class OpenAIClassification extends Task implements RunnableTask<OpenAIClassification.Output> {

    @Schema(
        title = "Text prompt",
        description = "The input text to classify"
    )
    @NotNull
    private Property<String> prompt;

    @Schema(
        title = "Classes",
        description = "The list of possible classes for classification"
    )
    @NotNull
    private Property<List<String>> classes;

    @Schema(
        title = "Apikey",
        description = "OpenAI API key"
    )
    @NotNull
    private Property<String> apikey;

    @Schema(
        title = "OpenAi model",
        description = "OpenAi model name"
    )
    @NotNull
    private Property<OpenAiChatModelName> openAiChatModelName = Property.of(OpenAiChatModelName.GPT_4_O_MINI);

    @Override
    public OpenAIClassification.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        // Render inputs
        String renderedPrompt = runContext.render(prompt).as(String.class)
            .orElseThrow();
        String renderedApiKey = runContext.render(apikey).as(String.class)
            .orElseThrow();
        List<String> renderedClasses = runContext.render(classes).asList(String.class);
        OpenAiChatModelName renderedOpenAiChatModelName = runContext.render(openAiChatModelName).as(OpenAiChatModelName.class)
            .orElseThrow();

        logger.info("Prompt: {}", renderedPrompt);
        logger.info("Classes: {}", renderedClasses);

        // Build the model
        ChatLanguageModel model = OpenAiChatModel.builder()
            .apiKey(renderedApiKey)
            .modelName(renderedOpenAiChatModelName)
            .build();

        // Construct a classification-specific prompt
        String classificationPrompt = renderedPrompt + "\nChoose one of the following classes: " + renderedClasses;

        // Generate the classification result
        String generatedClass = model.generate(classificationPrompt).trim();
        logger.info("Generated Class: {}", generatedClass);

        // Validate the result
        if (!renderedClasses.contains(generatedClass)) {
            throw new IllegalArgumentException("Generated class is not in the list of possible classes: " + generatedClass);
        }

        return Output.builder()
            .label(generatedClass)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Predicted label",
            description = "The label predicted by the model"
        )
        private final String label;
    }
}
