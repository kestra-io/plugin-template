package io.kestra.plugin.mailchimp;

import java.util.Map;
import java.util.stream.Collectors;

import lombok.Getter;

/** Error returned by the Mailchimp API (RFC 7807-like body: type, title, status, detail, errors[]). */
@Getter
public class MailchimpException extends RuntimeException {
    private final int status;
    private final String title;
    private final String detail;
    private final Map<String, String> fieldErrors;

    public MailchimpException(int status, String title, String detail, Map<String, String> fieldErrors) {
        super(message(status, title, detail, fieldErrors));
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.fieldErrors = fieldErrors == null ? Map.of() : fieldErrors;
    }

    static String message(int status, String title, String detail, Map<String, String> fieldErrors) {
        var sb = new StringBuilder().append(status);
        if (title != null && !title.isBlank()) {
            sb.append(' ').append(title);
        }
        if (detail != null && !detail.isBlank()) {
            sb.append(": ").append(detail);
        }
        if (fieldErrors != null && !fieldErrors.isEmpty()) {
            sb.append(" [")
                .append(fieldErrors.entrySet().stream().map(e -> e.getKey() + ": " + e.getValue()).collect(Collectors.joining(", ")))
                .append(']');
        }
        return sb.toString();
    }
}
