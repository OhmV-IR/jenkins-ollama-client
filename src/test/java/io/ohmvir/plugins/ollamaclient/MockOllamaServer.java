package io.ohmvir.plugins.ollamaclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Minimal stand-in for an Ollama server that records requests and replays queued responses.
 * Once the queue is empty every request gets an HTTP 500, so a client stuck in a loop fails fast.
 */
class MockOllamaServer implements AutoCloseable {
    record RecordedRequest(String method, String path, Headers headers, String body) {
        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private record QueuedResponse(int status, String body) {}

    private final HttpServer server;
    private final List<RecordedRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final Queue<QueuedResponse> responses = new ConcurrentLinkedQueue<>();

    MockOllamaServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(),
                    body));
            QueuedResponse response = responses.poll();
            if (response == null) {
                response = new QueuedResponse(500, "{\"error\":\"no response queued\"}");
            }
            byte[] responseBytes = response.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status(), responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://" + server.getAddress().getHostString() + ":"
                + server.getAddress().getPort();
    }

    void enqueue(int status, String body) {
        responses.add(new QueuedResponse(status, body));
    }

    List<RecordedRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
