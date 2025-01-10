package io.kestra.plugin.langchain;

import dev.langchain4j.model.openai.OpenAiChatModelName;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import static io.kestra.plugin.langchain.utils.ConstantTest.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for OpenAIClassification
 */
@KestraTest
class OpenAIClassificationTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        // GIVEN
        var classes = List.of("true", "false");
        RunContext runContext = runContextFactory.of(Map.of(
            "prompt", "Is 'This is a joke' a good joke?",
            "classes", classes,
            "apikey", OPENAI_DEMO_APIKEY,
            "openAiChatModelName", OpenAiChatModelName.GPT_4_O_MINI.name()
        ));

        // WHEN
        OpenAIClassification task = OpenAIClassification.builder()
            .prompt(new Property<>(PROPERTY_EXPRESSION_PROMPT))
            .classes(new Property<>("{{ classes }}"))
            .apikey(new Property<>(PROPERTY_EXPRESSION_APIKEY))
            .openAiChatModelName(new Property<>(PROPERTY_EXPRESSION_MODEL_NAME))
            .build();

        OpenAIClassification.Output runOutput = task.run(runContext);

        // THEN
        assertTrue(classes.contains(runOutput.getLabel()));
    }

    @Test
    void runWithInvalidClasses() {
        // GIVEN
        RunContext runContext = runContextFactory.of(Map.of(
            "prompt", "Is 'This is a joke' a good joke?",
            "classes", List.of("invalid1", "invalid2"),
            "apikey", OPENAI_DEMO_APIKEY,
            "openAiChatModelName", OpenAiChatModelName.GPT_4_O_MINI.name()
        ));

        // WHEN
        OpenAIClassification task = OpenAIClassification.builder()
            .prompt(new Property<>("{{ prompt }}"))
            .classes(new Property<>("{{ classes }}"))
            .apikey(new Property<>("{{ apikey }}"))
            .openAiChatModelName(new Property<>("{{ openAiChatModelName }}"))
            .build();

        // THEN
        Assertions.assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
    }
}
