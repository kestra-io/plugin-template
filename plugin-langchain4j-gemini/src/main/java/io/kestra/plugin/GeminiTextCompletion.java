package io.kestra.plugin;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Google Gemini Text Completion Task",
    description = "Generates text completion using Google Gemini models"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Text Completion Example",
            code = {
                "prompt: \"What is the capital of France?\"",
                "apiKey: \"gemini-api-key\""
            }
        )
    }
)
public class GeminiTextCompletion extends io.kestra.plugin.AbstractTextCompletion {
    @Schema(
        title = "Gemini Model",
        description = "Gemini-specific model configuration"
    )
    @NotNull
    private Property<String> modelName;

    @Override
    protected ChatLanguageModel createModel(RunContext runContext, String apiKey) throws IllegalVariableEvaluationException {
        String renderedModelName = runContext.render(modelName).as(String.class)
            .orElseThrow();

        return GoogleAiGeminiChatModel.builder()
            .apiKey(apiKey)
            .modelName(renderedModelName)
            .build();
    }
}