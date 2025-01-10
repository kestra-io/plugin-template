package io.kestra.plugin.langchain;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "LangChain4j Chat Memory Task",
    description = "Handles chat interactions with memory using LangChain4j"
)
@Plugin(
    examples = {
        @io.kestra.core.models.annotations.Example(
            title = "Chat Memory Example",
            code = {
                "prompt: \"Hello, my name is John\"",
                "model: \"gpt-4\"",
                "maxTokens: 300"
            }
        )
    }
)
public class OpenAIChatMemory extends Task implements RunnableTask<OpenAIChatMemory.Output> {

    @Schema(
        title = "User message",
        description = "The input message from the user"
    )
    @NotNull
    private Property<String> userMessage;

    @Schema(
        title = "API Key",
        description = "OpenAI API key"
    )
    @NotNull
    private Property<String> apikey;

    @Schema(
        title = "OpenAI Model",
        description = "OpenAI model name"
    )
    @NotNull
    private Property<OpenAiChatModelName> modelName = Property.of(OpenAiChatModelName.GPT_4_O_MINI);

    @Schema(
        title = "Max Tokens",
        description = "Maximum tokens for chat memory"
    )
    private Property<Integer> maxTokens;

    @Schema(
        title = "Chat Messages",
        description = "The list of chat messages for the current conversation"
    )
    private Property<List<ChatMessage>> chatMessages;

    @Override
    public OpenAIChatMemory.Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        // Render input properties
        String renderedUserMessage = runContext.render(userMessage).as(String.class)
            .orElseThrow();
        String renderedApiKey = runContext.render(apikey).as(String.class)
            .orElseThrow();
        OpenAiChatModelName renderedModelName = runContext.render(modelName).as(OpenAiChatModelName.class)
            .orElseThrow();
        int renderedMaxTokens = runContext.render(maxTokens).as(Integer.class).orElse(1000);

        // Render existing messages or initialize an empty list
        List<ChatMessage> renderedChatMessages = runContext.render(chatMessages).asList(ChatMessage.class);

        // Initialize ChatMemory
        InMemoryChatMemoryStore chatMemoryStore = new InMemoryChatMemoryStore();
        chatMemoryStore.updateMessages(UUID.randomUUID(), renderedChatMessages);

        ChatMemory chatMemory = TokenWindowChatMemory.builder()
            .maxTokens(renderedMaxTokens, new OpenAiTokenizer(renderedModelName))
            .chatMemoryStore(chatMemoryStore)
            .build();

        // Add user message to memory
        chatMemory.add(UserMessage.userMessage(renderedUserMessage));

        // Generate AI response
        ChatLanguageModel model = OpenAiChatModel.builder()
            .apiKey(renderedApiKey)
            .modelName(renderedModelName)
            .build();

        AiMessage aiResponse = model.generate(chatMemory.messages()).content();
        logger.info("AI Response: {}", aiResponse.text());

        // Add AI response to memory
        chatMemory.add(aiResponse);

        // Return updated messages
        return Output.builder()
            .aiResponse(aiResponse.text())
            .outputMessages(chatMemory.messages())
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "AI Response",
            description = "The generated response from the AI"
        )
        private final String aiResponse;

        @Schema(
            title = "Updated Messages",
            description = "The updated list of messages after the current interaction"
        )
        private final List<ChatMessage> outputMessages;
    }
}
