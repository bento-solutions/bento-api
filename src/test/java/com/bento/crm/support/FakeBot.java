package com.bento.crm.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for the Baileys bot's HTTP API, so tests exercise the backend's real client (auth
 * header, JSON shapes, error-code mapping) without WhatsApp. Sends succeed by default; a test can
 * queue scripted responses for the next sends.
 */
public final class FakeBot {

    public static final String API_KEY = "test-bot-api-key-0123456789abcdef0123456789abcdef";
    public static final String WEBHOOK_SECRET = "test-webhook-secret-0123456789abcdef0123456789ab";

    public record Request(String method, String path, JsonNode body) {
    }

    private static final Pattern SESSION_PATH = Pattern.compile("^/sessions/([0-9a-fA-F-]{36})(/(start|stop|logout|messages))?$");
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Map.Entry<Integer, String>> scriptedSends = new ConcurrentLinkedQueue<>();

    public FakeBot() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<Request> requests() {
        return requests;
    }

    public List<Request> sends() {
        return requests.stream().filter(r -> r.path().endsWith("/messages")).toList();
    }

    /** The next send answers {@code status} with {@code body} instead of succeeding. */
    public void failNextSend(int status, String body) {
        scriptedSends.add(Map.entry(status, body));
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (!("Bearer " + API_KEY).equals(auth)) {
                respond(exchange, 401, "{\"code\":\"UNAUTHORIZED\"}");
                return;
            }
            byte[] raw = exchange.getRequestBody().readAllBytes();
            JsonNode body = raw.length == 0 ? json.createObjectNode() : json.readTree(raw);
            String path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path, body));

            if (path.equals("/sessions")) {
                respond(exchange, 200, "[]");
                return;
            }
            Matcher m = SESSION_PATH.matcher(path);
            if (!m.matches()) {
                respond(exchange, 404, "{}");
                return;
            }
            String id = m.group(1);
            String action = m.group(3);
            if ("messages".equals(action)) {
                Map.Entry<Integer, String> scripted = scriptedSends.poll();
                if (scripted != null) {
                    respond(exchange, scripted.getKey(), scripted.getValue());
                    return;
                }
                String messageId = body.path("messageId").asText();
                String jid = body.path("to").asText().replaceAll("\\D", "") + "@s.whatsapp.net";
                respond(exchange, 200, "{\"wamid\":\"%s\",\"jid\":\"%s\",\"timestamp\":\"2026-01-01T00:00:00Z\"}"
                        .formatted(messageId, jid));
                return;
            }
            String state = switch (action == null ? "" : action) {
                case "start" -> body.hasNonNull("phoneNumber") ? "pairing" : "connecting";
                case "stop" -> "stopped";
                case "logout" -> "logged_out";
                default -> "stopped";
            };
            String code = "pairing".equals(state) ? "\"ABCD-EFGH\"" : "null";
            respond(exchange, 200, """
                    {"id":"%s","state":"%s","linked":false,"phoneNumber":null,"pairingCode":%s,
                     "pairingExpiresAt":null,"pairingAttempt":1,"maxPairingAttempts":5,"error":null}
                    """.formatted(id, state, code));
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
