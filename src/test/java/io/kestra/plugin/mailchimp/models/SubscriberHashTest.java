package io.kestra.plugin.mailchimp.models;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriberHashTest {
    @Test
    void knownVectorComputedOutsideTheCode() {
        // printf 'a@b.co' | md5sum
        assertThat(SubscriberHash.of("a@b.co"), equalTo("b33a54a5a598e6d3356166652048ada9"));
    }

    @Test
    void trimsAndLowercases() {
        // printf 'jane.doe+news@example.com' | md5sum
        var expected = "6a58e7286ac0e6944c137f0fb3f101b1";
        assertThat(SubscriberHash.of(" Jane.Doe+news@Example.COM "), equalTo(expected));
        assertThat(SubscriberHash.of("jane.doe+news@example.com"), equalTo(expected));
    }

    @Test
    void invalidEmailsAreRejected() {
        for (var email : new String[] {null, "", "  ", "no-at-sign"}) {
            assertThrows(IllegalArgumentException.class, () -> SubscriberHash.of(email), String.valueOf(email));
        }
    }
}
