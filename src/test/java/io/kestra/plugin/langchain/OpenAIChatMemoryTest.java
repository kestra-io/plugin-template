package io.kestra.plugin.langchain;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModelName;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

import static io.kestra.plugin.langchain.utils.ConstantTest.OPENAI_DEMO_APIKEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Unit test for OpenAIChatMemory
 */
@KestraTest
class OpenAIChatMemoryTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        // GIVEN: First prompt
        RunContext runContext = runContextFactory.of(Map.of(
            "userMessage", "Hello, my name is John",
            "apikey", OPENAI_DEMO_APIKEY,
            "modelName", OpenAiChatModelName.GPT_4_O_MINI.name(),
            "maxTokens", 1000,
            "chatMessages", List.of() // Initial empty chat messages
        ));

        OpenAIChatMemory firstTask = OpenAIChatMemory.builder()
            .userMessage(new Property<>("{{ userMessage }}"))
            .apikey(new Property<>("{{ apikey }}"))
            .modelName(new Property<>("{{ modelName }}"))
            .maxTokens(new Property<>("{{ maxTokens }}"))
            .chatMessages(new Property<>("{{ chatMessages }}"))
            .build();

        // WHEN: Run the first task
        OpenAIChatMemory.Output firstOutput = firstTask.run(runContext);

        // THEN: Validate the first response
        assertThat(firstOutput.getOutputMessages().size(), is(2)); // User and AI response

        List<ChatMessage> updatedMessages = firstOutput.getOutputMessages();

        // GIVEN: Second prompt using the updated messages
        runContext = runContextFactory.of(Map.of(
            "userMessage", "What's my name?",
            "apikey", OPENAI_DEMO_APIKEY,
            "modelName", OpenAiChatModelName.GPT_4_O_MINI.name(),
            "maxTokens", 300,
            "chatMessages", updatedMessages // Pass updated messages
        ));

        OpenAIChatMemory secondTask = OpenAIChatMemory.builder()
            .userMessage(new Property<>("{{ userMessage }}"))
            .apikey(new Property<>("{{ apikey }}"))
            .modelName(new Property<>("{{ modelName }}"))
            .maxTokens(new Property<>("{{ maxTokens }}"))
            .chatMessages(new Property<>("{{ chatMessages }}"))
            .build();

        // WHEN: Run the second task
        OpenAIChatMemory.Output secondOutput = secondTask.run(runContext);

        // THEN: Validate the second response
        assertThat(secondOutput.getAiResponse(), containsString("John"));
        assertThat(secondOutput.getOutputMessages().size(), is(4)); // Two user and two AI responses
    }
}
