package io.kestra.plugin.langchain;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
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
    title = "LangChain4j Structured Text Extraction",
    description = "Generates Structured Extraction text using LangChain4j"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Structured Extraction text Example",
            code = {
                "fields: name, date",
                "prompt: \"Hello, my name is John\"",
                "model: \"gpt-4\""
            }
        )
    }
)
public class OpenAIJSONStructuredExtraction extends Task implements RunnableTask<OpenAIJSONStructuredExtraction.Output> {
    @Schema(
        title = "Text prompt",
        description = "The input prompt for the language model"
    )
    @NotNull
    private Property<String> prompt;

    @Schema(
        title = "Json fields",
        description = "The list of fields to be extracted"
    )
    @NotNull
    private Property<List<String>> jsonFields;

    @Schema(
        title = "Name of the schema",
        description = "Schema name of the structured extraction"
    )
    @NotNull
    private Property<String> schemaName;

    @Schema(
        title = "Apikey",
        description = "OpenAI api key"
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
    public OpenAIJSONStructuredExtraction.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        // Render the task params
        String renderedPrompt = runContext.render(prompt).as(String.class)
            .orElseThrow();
        String renderedApiKey = runContext.render(apikey).as(String.class)
            .orElseThrow();
        String renderedSchemaName = runContext.render(schemaName).as(String.class)
            .orElseThrow();
        List<String> renderedFields = Property.asList(jsonFields, runContext, String.class);
        OpenAiChatModelName renderedOpenAiChatModelName = runContext.render(openAiChatModelName).as(OpenAiChatModelName.class)
            .orElseThrow();

        // Prepare the json structure response
        ResponseFormat responseFormat = ResponseFormat.builder()
            .type(ResponseFormatType.JSON)
            .jsonSchema(JsonSchema.builder()
                .name(renderedSchemaName)
                .rootElement(buildDynamicSchema(renderedFields))
                .build())
            .build();

        // Prepare Prompt Request
        ChatRequest chatRequest = ChatRequest.builder()
            .responseFormat(responseFormat)
            .messages(UserMessage.from(renderedPrompt))
            .build();

        ChatLanguageModel model = OpenAiChatModel.builder()
            .apiKey(renderedApiKey)
            .modelName(renderedOpenAiChatModelName)
            .build();

        // Generate text completion
        ChatResponse answer = model.chat(chatRequest);
        logger.info("Generated Completion !!");

        return Output.builder()
            .completion(answer.aiMessage().text())
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

    public static JsonObjectSchema buildDynamicSchema(List<String> renderedFields) {
        JsonObjectSchema.Builder schemaBuilder = JsonObjectSchema.builder();

        for (String field : renderedFields) {
            schemaBuilder.addStringProperty(field);
        }
        schemaBuilder.required(renderedFields);

        return schemaBuilder.build();
    }

}
