package io.kestra.task.templates;

import com.google.common.collect.ImmutableMap;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.annotation.MicronautTest;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.kestra.core.runners.RunContext;
import org.kestra.core.runners.RunOutput;

import javax.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * This test will only test the main task, this allow you to send any input
 * parameters to your task and test the returning behaviour easily.
 */
@MicronautTest
class ExampleTest {
    @Inject
    private ApplicationContext applicationContext;

    @Test
    void run() throws Exception {
        RunContext runContext = new RunContext(
            this.applicationContext,
            ImmutableMap.of("variable", "John Doe")
        );

        Example task = Example.builder()
            .format("Hello {{ variable }}")
            .build();

        RunOutput runOutput = task.run(runContext);

        assertThat(runOutput.getOutputs().get("example"), is(StringUtils.reverse("Hello John Doe")));
    }
}