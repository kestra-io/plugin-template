package io.kestra.plugin;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
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
    title = "Ollama Text Completion Task",
    description = "Generates text completion using Ollama models"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Text Completion Example",
            code = {
                "prompt: \"What is the capital of France?\"",
                "ollamaEndpoint: \"http://localhost:8000\""
            }
        )
    }
)
public class OllamaTextCompletion extends AbstractTextCompletion {

    private static final String TINY_DOLPHIN_MODEL = "tiny-dolphin";

    @Schema(
        title = "Ollama Endpoint",
        description = "The base URL for the Ollama API"
    )
    @NotNull
    private Property<String> ollamaEndpoint;


    @Override
    protected ChatLanguageModel createModel(RunContext runContext, String apiKey) throws IllegalVariableEvaluationException {
        String renderedUrl = runContext.render(ollamaEndpoint).as(String.class).orElseThrow();
        return OllamaChatModel.builder()
            .baseUrl(renderedUrl)
            .logRequests(true)
            .logResponses(true)
            .modelName(TINY_DOLPHIN_MODEL)
            .build();
    }
}
