package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.Json;
import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parts of newrelic-deployment the first suite leaves alone: the response reader on every JSON shape the API can
 * answer with (and every way a body can be malformed), the error messages that must stand on their own when the
 * response body is empty, the EU region, and the escaping of control characters in both request bodies.
 */
class NewRelicExtraIT {

    private static final String KEY = "NRAK-EXTRA-KEY";
    private static final String GUID = "MXxBUE18QVBQTElDQVRJT058OTk5";

    private static Atom fresh() throws Exception {
        return Atom.of();
    }

    private static Map<String, String> inputs(int port, Map<String, String> overrides) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("apiKey", KEY);
        map.put("region", "us");
        map.put("baseUrl", "http://127.0.0.1:" + port);
        map.put("mode", "graphql");
        map.put("entityGuid", GUID);
        map.put("applicationId", "");
        map.put("version", "3.1.4");
        map.put("timeoutSeconds", "10");
        map.putAll(overrides);
        return map;
    }

    private static AtomResult run(Atom target, int port, Map<String, String> overrides) throws Exception {
        return target.input(Json.write(inputs(port, overrides))).run();
    }

    @Test
    @DisplayName("a response with every JSON value shape is read, and the deployment id is still found")
    void readsEveryJsonShape() throws Exception {
        try (Fake nr = Fake.start()) {
            String body = "{\"data\":{\"changeTrackingCreateDeployment\":{\"deploymentId\":\"dep-7\"}},"
                    + "\"flagA\":true,\"flagB\":false,\"none\":null,"
                    + "\"neg\":-12,\"frac\":1.5,\"exp\":2e3,\"expneg\":-1.5E-2,"
                    + "\"emptyObj\":{},\"emptyArr\":[],"
                    + "\"arr\":[1,\"two\",false,null,{\"k\":\"v\"}],"
                    + "\"esc\":\"quote:\\\" backslash:\\\\ slash:\\/ b:\\b f:\\f n:\\n r:\\r t:\\t uni:\\u0041\"}";
            nr.push(200, body);
            AtomResult result = run(fresh(), nr.port(), Map.of());

            result.assertOk();
            assertEquals("dep-7", data(result, "deploymentId"));
            assertEquals("3.1.4", data(result, "version"));
        }
    }

    @Test
    @DisplayName("each malformed body is a 40008 that names the JSON reader's complaint")
    void rejectsMalformedJson() throws Exception {
        record Case(String body, String message) {}
        List<Case> cases = List.of(
                new Case("", "unexpected end of input"),
                new Case("{\"a\":1} trailing", "trailing characters"),
                new Case("{a:1}", "object key must be a string"),
                new Case("{\"a\" 1}", "expected ':' after object key"),
                new Case("{\"a\":1 \"b\":2}", "expected ',' or '}' in object"),
                new Case("[1 2]", "expected ',' or ']' in array"),
                new Case("\"open", "unterminated string"),
                new Case("\"bad\\q\"", "bad escape"),
                new Case("\"\\u12\"", "truncated"),
                new Case("\"\\uZZZZ\"", "bad \\u escape"),
                new Case("-", "bad number"),
                new Case("@", "unexpected character"),
                new Case("tru", "expected true"),
                new Case("{\"a\":", "unexpected end of input"),
                new Case("[", "unexpected end of input"));

        try (Fake nr = Fake.start()) {
            for (Case one : cases) {
                nr.clear();
                nr.push(200, one.body());
                AtomResult result = run(fresh(), nr.port(), Map.of());

                assertEquals("40008", result.field("errorCode"), one.body() + " should be rejected: " + result);
                assertTrue(result.field("message").contains(one.message()),
                        one.body() + ": message was " + result.field("message"));
            }
        }
    }

    @Test
    @DisplayName("a 404 in the EU region names the EU host, the application and the id")
    void euNotFoundNamesTheHostAndApplication() throws Exception {
        try (Fake nr = Fake.start()) {
            nr.push(404, "");
            AtomResult result = run(fresh(), nr.port(), Map.of("region", "eu", "mode", "v2",
                    "applicationId", "4242", "baseUrl", "http://127.0.0.1:" + nr.port() + "/"));

            assertEquals("40003", result.field("errorCode"), result.toString());
            String message = result.field("message");
            assertTrue(message.contains("application 4242"), message);
            assertTrue(message.contains("api.eu.newrelic.com"), "the message should name the EU host: " + message);
            assertFalse(message.endsWith(": "), "an empty body must not leave a dangling colon: " + message);
        }
    }

    @Test
    @DisplayName("401 and 429 with an empty body still carry a usable message")
    void authAndRateLimitWithoutABody() throws Exception {
        try (Fake nr = Fake.start()) {
            nr.push(401, "");
            AtomResult denied = run(fresh(), nr.port(), Map.of());
            assertEquals("40006", denied.field("errorCode"));
            assertTrue(denied.field("message").contains("rejected (HTTP/401)"),
                    "message: " + denied.field("message"));

            nr.clear();
            nr.push(429, "");
            AtomResult limited = run(fresh(), nr.port(), Map.of());
            assertEquals("40004", limited.field("errorCode"));
            assertFalse(limited.field("message").contains("Retry-After"),
                    "no header means no Retry-After claim: " + limited.field("message"));
            assertTrue(limited.field("message").contains("rate-limited"),
                    "message: " + limited.field("message"));
        }
    }

    @Test
    @DisplayName("a body that parses but has no deployment id is 40007, whatever shape the data has")
    void parsesButHasNoId() throws Exception {
        record Case(String body, String mode) {}
        List<Case> cases = List.of(
                new Case("{}", "graphql"),
                new Case("[]", "graphql"),
                new Case("{\"data\":\"not-an-object\"}", "graphql"),
                new Case("{\"deployment\":{\"id\":true}}", "v2"));

        try (Fake nr = Fake.start()) {
            for (Case one : cases) {
                nr.clear();
                nr.push(200, one.body());
                AtomResult result = run(fresh(), nr.port(), Map.of("mode", one.mode(),
                        "applicationId", "v2".equals(one.mode()) ? "9" : ""));

                assertEquals("40007", result.field("errorCode"), one.body() + ": " + result);
                assertTrue(result.field("message").contains("carried no deployment id"),
                        "message: " + result.field("message"));
            }
        }
    }

    @Test
    @DisplayName("a GraphQL errors array whose element is a string reports that string")
    void graphqlErrorThatIsNotAnObject() throws Exception {
        try (Fake nr = Fake.start()) {
            nr.push(200, "{\"errors\":[\"top level boom\"]}");
            AtomResult result = run(fresh(), nr.port(), Map.of());

            assertEquals("40005", result.field("errorCode"), result.toString());
            assertTrue(result.field("message").contains("top level boom"), result.field("message"));

            nr.clear();
            nr.push(200, "{\"errors\":[]}");
            AtomResult empty = run(fresh(), nr.port(), Map.of());
            assertEquals("40007", empty.field("errorCode"), "an empty errors array is not a rejection: " + empty);
        }
    }

    @Test
    @DisplayName("control characters in the v2 changelog are escaped, and the body parses back to the input")
    void escapesControlCharactersInTheV2Body() throws Exception {
        String nasty = "line\r\nback\bspace\fform\u0001bell \"q\" back\\slash";
        try (Fake nr = Fake.start()) {
            nr.push(201, "{\"deployment\":{\"id\":77}}");
            AtomResult result = run(fresh(), nr.port(), Map.of("mode", "v2", "applicationId", "8",
                    "changelog", nasty));

            result.assertOk();
            Fake.Request request = nr.next();
            Map<String, Object> document = Json.parseObject(request.body());
            Map<String, Object> deployment = (Map<String, Object>) document.get("deployment");
            assertEquals(nasty, deployment.get("changelog"), "the changelog did not survive escaping");
            assertEquals("3.1.4", deployment.get("revision"));
        }
    }

    @Test
    @DisplayName("a baseUrl that cannot form a URI is a 40002 before anything is sent")
    void refusesAnUnusableBaseUrl() throws Exception {
        AtomResult result = run(fresh(), 1, Map.of("baseUrl", "http://bad host"));

        assertEquals("40002", result.field("errorCode"), result.toString());
        assertTrue(result.field("message").contains("cannot build a request URL"),
                result.field("message"));
    }

    @Test
    @DisplayName("a long error body is summarised instead of pasted whole")
    void truncatesALongErrorBody() throws Exception {
        try (Fake nr = Fake.start()) {
            String longBody = "upstream said " + "x".repeat(1000);
            nr.push(503, longBody);
            AtomResult result = run(fresh(), nr.port(), Map.of());

            String message = result.field("message");
            assertEquals("40005", result.field("errorCode"));
            assertTrue(message.contains("upstream said"), message);
            assertTrue(message.endsWith("..."), "the snippet should be cut and marked: " + message);
            assertTrue(message.length() < 500, "the message kept " + message.length() + " characters");
        }
    }

    private static String data(AtomResult run, String key) {
        Map<String, Object> document = Json.parseObject(run.file("output.json"));
        Map<String, Object> data = (Map<String, Object>) document.get("data");
        Map<String, Object> node = (Map<String, Object>) data.get(key);
        return String.valueOf(node.get("value"));
    }

    /** A fake New Relic that answers from a queue and keeps the last request. */
    private static final class Fake implements AutoCloseable {

        private final HttpServer server;
        private final Deque<Answer> answers = new ArrayDeque<>();
        private final List<Request> requests = new ArrayList<>();

        private record Answer(int status, String body) {}

        private record Request(String path, String body) {}

        private Fake(HttpServer server) {
            this.server = server;
        }

        static Fake start() throws IOException {
            Fake fake = new Fake(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
            fake.server.createContext("/", fake::handle);
            fake.server.start();
            return fake;
        }

        int port() {
            return server.getAddress().getPort();
        }

        synchronized void push(int status, String body) {
            answers.add(new Answer(status, body));
        }

        synchronized void clear() {
            answers.clear();
            requests.clear();
        }

        synchronized Request next() {
            return requests.isEmpty() ? null : requests.remove(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Answer answer;
            synchronized (this) {
                requests.add(new Request(exchange.getRequestURI().getPath(), body));
                answer = answers.isEmpty() ? new Answer(500, "no answer queued") : answers.removeFirst();
            }
            byte[] payload = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (payload.length == 0) {
                exchange.sendResponseHeaders(answer.status(), -1);
            } else {
                exchange.sendResponseHeaders(answer.status(), payload.length);
                exchange.getResponseBody().write(payload);
            }
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
