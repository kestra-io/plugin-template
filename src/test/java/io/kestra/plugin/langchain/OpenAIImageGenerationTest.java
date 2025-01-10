package io.kestra.plugin.langchain;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import dev.langchain4j.model.openai.OpenAiImageModelName;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.kestra.plugin.langchain.utils.ConstantTest.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Unit test for OpenAIImageGeneration
 */
@KestraTest
class OpenAIImageGenerationTest {

    @Inject
    private RunContextFactory runContextFactory;

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        // Start WireMock server
        wireMockServer = new WireMockServer();
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void run() throws Exception {
        // Stub the OpenAI API response
        stubFor(post(urlEqualTo(OPENAI_IMAGE_GENERATION_URI))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"data\": [{\"url\": \"https://mock-image-url.com/image.png\"}]}")));

        // GIVEN
        RunContext runContext = runContextFactory.of(Map.of(
            "prompt", TEST_PROMPT_IMAGE_GENERATION,
            "apikey", OPENAI_DEMO_APIKEY,
            "openAiImageModelName", OpenAiImageModelName.DALL_E_2.name(),
            "apiUrl", "http://localhost:" + wireMockServer.port()
        ));

        // WHEN
        OpenAIImageGeneration task = OpenAIImageGeneration.builder()
            .prompt(new Property<>(PROPERTY_EXPRESSION_PROMPT))
            .apikey(new Property<>(PROPERTY_EXPRESSION_APIKEY))
            .openAiImageModelName(new Property<>("{{ openAiImageModelName }}"))
            .apiUrl(new Property<>("{{ apiUrl }}"))
            .build();

        OpenAIImageGeneration.Output runOutput = task.run(runContext);

        // THEN
        assertThat(runOutput.getCompletion(), is("https://mock-image-url.com/image.png"));

        // Verify WireMock interaction
        verify(postRequestedFor(urlEqualTo(OPENAI_IMAGE_GENERATION_URI))
            .withRequestBody(matchingJsonPath("$.prompt", equalTo(TEST_PROMPT_IMAGE_GENERATION)))
            .withHeader("Authorization", matching("Bearer "+ OPENAI_DEMO_APIKEY)));
    }


}
