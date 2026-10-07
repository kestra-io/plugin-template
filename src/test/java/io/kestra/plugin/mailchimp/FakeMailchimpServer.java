package io.kestra.plugin.mailchimp;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Minimal scripted HTTP server standing in for the Mailchimp API in tests (no extra dependency). */
public class FakeMailchimpServer implements AutoCloseable {
    public record Response(int status, String body, Map<String, String> headers, long delayMillis) {
        public Response(int status, String body, Map<String, String> headers) {
            this(status, body, headers, 0);
        }

        public static Response json(int status, String body) {
            return new Response(status, body, Map.of("Content-Type", "application/json"));
        }

        public static Response empty(int status) {
            return new Response(status, "", Map.of());
        }

        public static Response redirect(String location) {
            return new Response(302, "", Map.of("Location", location));
        }

        /** Answers only after the given delay (to provoke client timeouts). */
        public Response delayed(long millis) {
            return new Response(status, body, headers, millis);
        }

        public Response withHeader(String name, String value) {
            var h = new HashMap<>(headers);
            h.put(name, value);
            return new Response(status, body, h, delayMillis);
        }
    }

    public record Recorded(String method, String path, String query, Map<String, String> headers, String body) {
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private final HttpServer server;
    private final Map<String, Deque<Response>> scripts = new HashMap<>();
    private final Map<String, Response> last = new HashMap<>();
    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());

    public FakeMailchimpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // a delayed answer must not block the next request
        server.start();
    }

    /** Scripts responses for METHOD + path; sequences are served in order, the last one then repeats. */
    public FakeMailchimpServer on(String method, String path, Response... responses) {
        synchronized (scripts) {
            scripts.computeIfAbsent(method + " " + path, k -> new ArrayDeque<>()).addAll(List.of(responses));
        }
        return this;
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Base URL as the client expects it (includes the API version segment). */
    public String baseUrl() {
        return url() + "/3.0";
    }

    public List<Recorded> requests() {
        return new ArrayList<>(requests);
    }

    private void handle(HttpExchange ex) throws IOException {
        var body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var headers = new LinkedHashMap<String, String>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), v.getFirst()));
        var path = ex.getRequestURI().getRawPath();
        requests.add(new Recorded(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(), headers, body));

        var key = ex.getRequestMethod() + " " + path;
        Response response;
        synchronized (scripts) {
            var queue = scripts.get(key);
            if (queue != null && !queue.isEmpty()) {
                response = queue.poll();
                last.put(key, response);
            } else {
                response = last.getOrDefault(key, Response.json(404, "{\"title\":\"Not Scripted\",\"status\":404}"));
            }
        }
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        response.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        var bytes = response.body().getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
