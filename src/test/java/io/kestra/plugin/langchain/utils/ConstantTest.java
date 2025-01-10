package io.kestra.plugin.langchain.utils;

import lombok.experimental.UtilityClass;

@UtilityClass
public class ConstantTest {

    public final static String OPENAI_DEMO_APIKEY = "demo";
    public final static String PROPERTY_EXPRESSION_PROMPT = "{{ prompt }}";
    public final static String PROPERTY_EXPRESSION_APIKEY = "{{ apikey }}";
    public final static String PROPERTY_EXPRESSION_MODEL_NAME = "{{ openAiChatModelName }}";

    // TEXT COMPLETION TEST
    public final static String PROMPT_TEXT_COMPLETION = "What is the capital of France?";
    public final static String EXPECTED_RESULT = "The capital of France is Paris.";

    // IMAGE GENERATION TEST
    public final static String TEST_PROMPT_IMAGE_GENERATION = "Donald Duck in New York, cartoon style";
    public final static String OPENAI_IMAGE_GENERATION_URI = "/v1/images/generations";

    // JSON STRUCTURED EXTRACTION TEST
    public final static String TEST_PROMPT_STRUCTURED_EXTRACTION = "Extract structured data: My name is John and I was born on 2000-01-01";
    public final static String TEST_SCHEMA_NAME = "PersonSchema";
}
