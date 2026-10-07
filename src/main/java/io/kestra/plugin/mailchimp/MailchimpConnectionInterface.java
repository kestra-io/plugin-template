package io.kestra.plugin.mailchimp;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Pattern;

import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;

/**
 * A task and a trigger have different base classes, so both declare the connection fields
 * and share only the client-building logic through this interface.
 */
public interface MailchimpConnectionInterface {
    Pattern SERVER = Pattern.compile("^[a-z]{2,6}[0-9]{1,3}$");

    Property<String> getApiKey();

    Property<String> getAccessToken();

    Property<String> getServer();

    Property<String> getBaseUrl();

    Property<Integer> getMaxRetries();

    Property<Duration> getTimeout();

    static MailchimpClient client(RunContext runContext, MailchimpConnectionInterface connection) throws Exception {
        var baseUrl = baseUrl(runContext, connection);
        var token = token(runContext, connection);
        var maxRetries = runContext.render(connection.getMaxRetries()).as(Integer.class).orElse(3);
        if (maxRetries < 0) {
            throw new IllegalArgumentException("'maxRetries' must be >= 0");
        }
        var timeout = runContext.render(connection.getTimeout()).as(Duration.class).orElse(Duration.ofSeconds(120));
        return new MailchimpClient(baseUrl, token, maxRetries, timeout, Thread::sleep);
    }

    private static String token(RunContext runContext, MailchimpConnectionInterface connection) throws Exception {
        var apiKey = runContext.render(connection.getApiKey()).as(String.class).filter(s -> !s.isBlank());
        var accessToken = runContext.render(connection.getAccessToken()).as(String.class).filter(s -> !s.isBlank());
        if (apiKey.isPresent() == accessToken.isPresent()) {
            throw new IllegalArgumentException("Set exactly one of 'apiKey' or 'accessToken'");
        }
        return apiKey.orElseGet(accessToken::get);
    }

    /** API root, including the version segment, e.g. {@code https://us19.api.mailchimp.com/3.0}. */
    static String baseUrl(RunContext runContext, MailchimpConnectionInterface connection) throws Exception {
        token(runContext, connection); // validates "exactly one credential" before anything else

        var explicitServer = runContext.render(connection.getServer()).as(String.class).filter(s -> !s.isBlank());
        if (explicitServer.isPresent() && !SERVER.matcher(explicitServer.get()).matches()) {
            throw new IllegalArgumentException("Invalid 'server' '" + explicitServer.get() + "': expected a Mailchimp data center such as 'us19'");
        }

        var rBaseUrl = runContext.render(connection.getBaseUrl()).as(String.class).filter(s -> !s.isBlank());
        if (rBaseUrl.isPresent()) {
            var url = rBaseUrl.get().replaceAll("/+$", "");
            URI uri;
            try {
                uri = URI.create(url);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("'baseUrl' is not a valid URL");
            }
            var local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if (!"https".equals(uri.getScheme()) && !local) {
                throw new IllegalArgumentException("'baseUrl' must use https (http is only allowed for localhost / 127.0.0.1)");
            }
            return url;
        }

        var apiKey = runContext.render(connection.getApiKey()).as(String.class).filter(s -> !s.isBlank());
        var server = runContext.render(connection.getServer()).as(String.class).filter(s -> !s.isBlank())
            .or(() -> apiKey.filter(k -> k.contains("-")).map(k -> k.substring(k.lastIndexOf('-') + 1)))
            .orElseThrow(() -> new IllegalArgumentException("'server' is required: use an apiKey ending in '-<data center>' (for example '-us19') or set 'server' (see https://login.mailchimp.com/oauth2/metadata for OAuth tokens)"));
        if (!SERVER.matcher(server).matches()) {
            throw new IllegalArgumentException("Invalid 'server' '" + server + "': expected a Mailchimp data center such as 'us19'");
        }
        return "https://" + server + ".api.mailchimp.com/3.0";
    }
}
