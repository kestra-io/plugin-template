package io.kestra.plugin.langchain;

import dev.langchain4j.model.openai.OpenAiChatModelName;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import org.junit.jupiter.api.Test;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

import static io.kestra.plugin.langchain.utils.ConstantTest.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@KestraTest
class OpenAIJSONStructuredExtractionTest {

    @Inject
    private RunContextFactory runContextFactory;

    @Test
    void run() throws Exception {
        // GIVEN
        RunContext runContext = runContextFactory.of(Map.of(
            "prompt", TEST_PROMPT_STRUCTURED_EXTRACTION,
            "jsonFields", List.of("name", "date"),
            "schemaName", TEST_SCHEMA_NAME,
            "apikey", OPENAI_DEMO_APIKEY,
            "openAiChatModelName", OpenAiChatModelName.GPT_4_O_MINI.name()

            ));

        // WHEN
        OpenAIJSONStructuredExtraction task = OpenAIJSONStructuredExtraction.builder()
            .prompt(new Property<>(PROPERTY_EXPRESSION_PROMPT))
            .jsonFields(new Property<>("{{ jsonFields }}"))
            .schemaName(new Property<>("{{ schemaName }}"))
            .apikey(new Property<>(PROPERTY_EXPRESSION_APIKEY))
            .openAiChatModelName(new Property<>(PROPERTY_EXPRESSION_MODEL_NAME))
            .build();

        OpenAIJSONStructuredExtraction.Output runOutput = task.run(runContext);

        // THEN
        String expectedOutput = "{\"name\":\"John\",\"date\":\"2000-01-01\"}";
        assertThat(runOutput.getCompletion(), is(expectedOutput));
    }

}
