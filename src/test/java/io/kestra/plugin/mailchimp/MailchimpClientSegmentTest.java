package io.kestra.plugin.mailchimp;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MailchimpClientSegmentTest {
    @Test
    void encodes() {
        assertThat(MailchimpClient.segment("a b"), equalTo("a%20b"));
        assertThat(MailchimpClient.segment("a?b"), equalTo("a%3Fb"));
        assertThat(MailchimpClient.segment("a#b"), equalTo("a%23b"));
        assertThat(MailchimpClient.segment("café"), equalTo("caf%C3%A9"));
        assertThat(MailchimpClient.segment("a+b"), equalTo("a%2Bb"));
    }

    @Test
    void normalIdUnchanged() {
        assertThat(MailchimpClient.segment("f3a9c1d2e4"), equalTo("f3a9c1d2e4"));
        assertThat(MailchimpClient.segment("abc-123_x"), equalTo("abc-123_x"));
    }

    @Test
    void rejectsUnsafe() {
        for (var bad : new String[] {null, "", "  ", "a/b", "/", ".", ".."}) {
            assertThrows(IllegalArgumentException.class, () -> MailchimpClient.segment(bad));
        }
    }
}
