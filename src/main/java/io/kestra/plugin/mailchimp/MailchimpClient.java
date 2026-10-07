package io.kestra.plugin.mailchimp;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import io.kestra.core.serializers.JacksonMapper;



/**
 * Thin Mailchimp Marketing API client: bearer auth, no redirect following, retry with exponential backoff and
 * error mapping to {@link MailchimpException}.
 *
 * <p>It uses the JDK {@link HttpClient} rather than Kestra's own one: the latter sits on Apache HttpClient, whose
 * built-in retry strategy silently replays 429/503 (including POST) and cannot be turned off, which would break
 * the retry rules below.
 */
public class MailchimpClient implements Closeable {
    /** Injected so tests do not actually sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final Duration BASE_DELAY = Duration.ofSeconds(1);
    static final Duration MAX_DELAY = Duration.ofSeconds(30);
    private static final Set<String> IDEMPOTENT = Set.of("GET", "PUT");

    private final HttpClient http;
    private final String baseUrl;
    private final String token;
    private final int maxRetries;
    private final Duration timeout;
    private final Sleeper sleeper;

    public MailchimpClient(String baseUrl, String token, int maxRetries, Duration timeout, Sleeper sleeper) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.maxRetries = maxRetries;
        this.timeout = timeout;
        this.sleeper = sleeper;
        this.http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER) // a redirect to another host must never receive the credential
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /** @param path relative to the API root, e.g. {@code /lists/abc/members} */
    public JsonNode send(String method, String path, Map<String, String> query, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path, query))
            .timeout(timeout)
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
        if (body != null) {
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JacksonMapper.ofJson().writeValueAsString(body)));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        var request = builder.build();
        var retryable = IDEMPOTENT.contains(method.toUpperCase());

        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                // timeouts and connection errors: only safe to repeat for idempotent calls
                if (retryable && attempt < maxRetries) {
                    sleeper.sleep(delay(attempt, null));
                    continue;
                }
                throw e;
            }

            var status = response.statusCode();
            if (status >= 200 && status < 300) {
                try {
                    return parse(response.body());
                } catch (IOException e) {
                    throw new MailchimpException(status, "Invalid JSON response", null, null);
                }
            }
            if (attempt < maxRetries && (status == 429 || (status >= 500 && retryable))) {
                sleeper.sleep(delay(attempt, response.headers().firstValue("Retry-After").orElse(null)));
                continue;
            }
            throw error(status, response);
        }
    }

    /**
     * Calls {@code GET path} page by page ({@code count}/{@code offset}) and hands each page's {@code arrayField}
     * array to {@code pageHandler}. Stops on a short page or once {@code total_items} items were read.
     */
    public void getPaged(String path, Map<String, String> query, int pageSize, Consumer<JsonNode> pageHandler, String arrayField) throws Exception {
        getPagedWhile(path, query, pageSize, page -> {
            var items = page.path(arrayField);
            if (items.isArray() && !items.isEmpty()) {
                pageHandler.accept(items);
            }
            return true;
        }, arrayField);
    }

    /**
     * Like {@link #getPaged} but hands the whole page (so callers can read {@code total_items}) to {@code pageHandler},
     * including an empty one, and stops as soon as it returns {@code false} so no further page is requested.
     */
    public void getPagedWhile(String path, Map<String, String> query, int pageSize, Predicate<JsonNode> pageHandler, String arrayField) throws Exception {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("'pageSize' must be > 0");
        }
        long fetched = 0;
        for (long offset = 0; ; offset += pageSize) {
            var q = new LinkedHashMap<String, String>(query == null ? Map.of() : query);
            q.put("count", String.valueOf(pageSize));
            q.put("offset", String.valueOf(offset));

            var page = send("GET", path, q, null);
            var items = page.path(arrayField);
            if (!pageHandler.test(page) || !items.isArray() || items.isEmpty()) {
                return;
            }
            fetched += items.size();

            // total_items can shrink while we paginate, so it is re-read on every page
            if (items.size() < pageSize || (page.has("total_items") && fetched >= page.get("total_items").asLong())) {
                return;
            }
        }
    }

    /** Percent-encodes one path segment (list id, campaign id, member hash...) so it cannot alter the request path. */
    public static String segment(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Path segment must not be blank");
        }
        if (value.contains("/") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid path segment '" + value + "'");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private URI uri(String path, Map<String, String> query) {
        var sb = new StringBuilder(baseUrl).append(path);
        if (query != null && !query.isEmpty()) {
            sb.append('?');
            var first = true;
            for (var e : query.entrySet()) {
                sb.append(first ? "" : "&").append(enc(e.getKey())).append('=').append(enc(e.getValue()));
                first = false;
            }
        }
        return URI.create(sb.toString());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static JsonNode parse(String body) throws IOException {
        return body == null || body.isBlank() ? NullNode.getInstance() : JacksonMapper.ofJson().readTree(body);
    }

    /** Exponential backoff (1 s, factor 2, cap 30 s) with jitter in [50%, 100%]; Retry-After wins when present. */
    static Duration delay(int attempt, String retryAfter) {
        if (retryAfter != null) {
            try {
                return min(Duration.ofSeconds(Math.max(0, Long.parseLong(retryAfter.trim()))), MAX_DELAY);
            } catch (NumberFormatException ignored) {
                // HTTP-date form is not used by Mailchimp; fall back to backoff
            }
        }
        var exp = min(BASE_DELAY.multipliedBy(1L << Math.min(attempt, 10)), MAX_DELAY);
        return Duration.ofMillis((long) (exp.toMillis() * ThreadLocalRandom.current().nextDouble(0.5, 1.0)));
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static MailchimpException error(int status, HttpResponse<String> response) {
        String title = null;
        String detail = null;
        var fields = new LinkedHashMap<String, String>();
        try {
            var json = parse(response.body());
            title = json.path("title").asText(null);
            detail = json.path("detail").asText(null);
            for (var err : json.path("errors")) {
                fields.put(err.path("field").asText("?"), err.path("message").asText(""));
            }
        } catch (IOException ignored) {
            // non-JSON body (Mailchimp may send none on 429/403): status text is used instead
        }
        if (title == null || title.isBlank()) {
            title = reason(status);
        }
        if (status == 401) {
            detail = "Mailchimp credentials invalid or revoked" + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
        } else if (status >= 300 && status < 400) {
            detail = "redirect to '" + response.headers().firstValue("Location").orElse("?") + "' not followed";
        }
        return new MailchimpException(status, title, detail, fields);
    }

    private static String reason(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> status >= 300 && status < 400 ? "Redirect" : "HTTP error";
        };
    }

    @Override
    public void close() {
        http.close();
    }
}
