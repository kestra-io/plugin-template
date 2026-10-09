package io.kestra.plugin.mailchimp.models;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Mailchimp's member id: the MD5 hex of the trimmed, lower-cased email address. */
public final class SubscriberHash {
    private SubscriberHash() {
    }

    public static String of(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new IllegalArgumentException("Invalid email address '" + email + "'");
        }
        try {
            var md5 = MessageDigest.getInstance("MD5"); // not a security use: Mailchimp's own member id scheme
            return HexFormat.of().formatHex(md5.digest(email.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
