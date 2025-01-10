package io.kestra.plugin.langchain;


import dev.langchain4j.model.openai.OpenAiChatModelName;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import org.junit.jupiter.api.Test;
import jakarta.inject.Inject;

import java.util.Map;

import static io.kestra.plugin.langchain.utils.ConstantTest.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Unit test for LangChainTextCompletion
 */
@KestraTest
class OpenAITextCompletionTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        // GIVEN
        RunContext runContext = runContextFactory.of(Map.of(
            "prompt", PROMPT_TEXT_COMPLETION,
            "apikey", OPENAI_DEMO_APIKEY,
            "openAiChatModelName", OpenAiChatModelName.GPT_4_O_MINI.name()
        ));

        // WHEN
        OpenAITextCompletion task = OpenAITextCompletion.builder()
            .prompt(new Property<>(PROPERTY_EXPRESSION_PROMPT))
            .apikey(new Property<>(PROPERTY_EXPRESSION_APIKEY))
            .openAiChatModelName(new Property<>("{{ openAiChatModelName }}"))
            .build();

        OpenAITextCompletion.Output runOutput = task.run(runContext);

        // THEN
        assertThat(runOutput.getCompletion(), is(EXPECTED_RESULT));
    }

}
