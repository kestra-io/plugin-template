package io.kestra.plugin.langchain;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.model.image.ImageModel;
import dev.langchain4j.model.openai.OpenAiImageModel;
import dev.langchain4j.model.openai.OpenAiImageModelName;
import dev.langchain4j.model.output.Response;
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
    title = "LangChain4j OpenAI Image generation Task",
    description = "Image generation using LangChain4j"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Image generation Example",
            code = {
                "prompt: \"Donald Duck in New York, cartoon style\"",
                "model: \"dall-e-3\""
            }
        )
    }
)
public class OpenAIImageGeneration extends Task implements RunnableTask<OpenAIImageGeneration.Output> {
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
        description = "OpenAi image generation model name"
    )
    @NotNull
    private Property<OpenAiImageModelName> openAiImageModelName = Property.of(OpenAiImageModelName.DALL_E_3);

    @Schema(
        title = "Base API URL",
        description = "The base URL for the OpenAI API (default: https://api.openai.com)."
    )
    private Property<String> apiUrl;

    @Override
    public OpenAIImageGeneration.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        // Render the input prompt & apikey & model name
        String renderedPrompt = runContext.render(prompt).as(String.class).orElseThrow();
        String renderedApiKey = runContext.render(apikey).as(String.class)
            .orElseThrow();
        String renderedApiUrl = runContext.render(apiUrl).as(String.class).orElse("https://api.openai.com");

        OpenAiImageModelName renderedOpenAiImageModelName = runContext.render(openAiImageModelName).as(OpenAiImageModelName.class)
            .orElseThrow();

        logger.info("Prompt: {}", renderedPrompt);
        ImageModel model = OpenAiImageModel.builder()
            .apiKey(renderedApiKey)
            .modelName(renderedOpenAiImageModelName)
            .baseUrl(renderedApiUrl + "/v1")
            .build();

        // Generate text completion
        Response<Image> generatedImage = model.generate(renderedPrompt);

        String imageUrl = generatedImage.content().url().toString();
        logger.info("Image generated url : [{}]", imageUrl);


        return Output.builder()
            .completion(imageUrl)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Generated image completion",
            description = "The result of the OpenAI image generation URL"
        )
        private final String completion;
    }
}
