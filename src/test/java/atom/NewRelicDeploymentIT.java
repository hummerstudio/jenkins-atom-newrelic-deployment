package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The newrelic-deployment atom against a fake New Relic that records every request and answers from a queue.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made through {@code tools/check.py}: the GraphQL document is
 * rebuilt from the field list (order, quoting, escaping) and parsed back field by field, the v2 body is compared as a
 * JSON document, the API key is compared against what the case put in, and the status mapping is checked on every
 * branch — 401/403, 429 with Retry-After, 404, 503, a non-JSON body, a redirect that must not be followed, a timeout.
 */
class NewRelicDeploymentIT {

    private static final String KEY = "NRAK-SECRET-KEY-1234";
    private static final String GUID = "MXxBUE18QVBQTElDQVRJT058MTIzNDU2Nzg5";
    private static final Pattern FIELD = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*): (?:\"((?:[^\"\\\\]|\\\\.)*)\"|(-?[0-9]+))");
    private static final Pattern DEPLOYMENT = Pattern.compile(
            "changeTrackingCreateDeployment\\(deployment: \\{(.*)\\}\\) \\{deploymentId\\}", Pattern.DOTALL);

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("graphql: a minimal deployment carries version, entityGuid and the given timestamp")
    void graphqlMinimal() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(200, "{\"data\":{\"changeTrackingCreateDeployment\":{\"deploymentId\":\"dep-001\"}}}");
            AtomResult run = run(nr, Map.of("timestampSeconds", "1700000000"));

            ok(run);
            Record request = nr.next();
            assertEquals("/graphql", request.path);
            assertEquals(KEY, request.headers.get("x-api-key"), "X-Api-Key is the job's key");
            assertTrue(request.headers.getOrDefault("content-type", "").startsWith("application/json"),
                    "Content-Type json: " + request.headers.get("content-type"));

            Map<String, Object> envelope = Json.parseObject(request.bodyText);
            String query = String.valueOf(envelope.get("query"));
            assertEquals(Map.of("version", "1.2.3", "entityGuid", GUID), gqlFields(query));
            assertEquals(1700000000L, gqlTimestamp(query), "timestamp is the given Unix second");
            String expected = expectedGraphql(List.of(
                    new String[] {"version", "1.2.3"}, new String[] {"entityGuid", GUID}), 1700000000L);
            assertEquals(expected, query, "the document matches the rebuilt one");
            assertEquals(List.of("query"), new ArrayList<>(envelope.keySet()), "envelope has only the query key");
            assertEquals("dep-001", data(run, "deploymentId"));
            assertEquals("200", data(run, "httpCode"));
            assertEquals(0, nr.count(), "exactly one request");
        }
    }

    @Test
    @DisplayName("graphql: an empty timestampSeconds omits the field instead of sending 0 or millis")
    void graphqlDefaultTimestamp() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(200, "{\"data\":{\"changeTrackingCreateDeployment\":{\"deploymentId\":\"dep-000\"}}}");
            AtomResult run = run(nr, Map.of());

            ok(run);
            String query = String.valueOf(Json.parseObject(nr.next().bodyText).get("query"));
            assertEquals(Map.of("version", "1.2.3", "entityGuid", GUID), gqlFields(query));
            assertEquals(null, gqlTimestamp(query), "no timestamp field at all");
            assertFalse(query.contains("timestamp"), "the word timestamp is absent: " + query);
            assertEquals(expectedGraphql(List.of(
                    new String[] {"version", "1.2.3"}, new String[] {"entityGuid", GUID}), null), query);
            assertEquals(0, nr.count(), "exactly one request");
        }
    }

    @Test
    @DisplayName("graphql: every field survives quoting and escaping, in the documented order")
    void graphqlFullEscaping() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(200, "{\"data\":{\"changeTrackingCreateDeployment\":{\"deploymentId\":\"dep-002\"}}}");
            String nasty = "release \"2.0\" \\ 中文\n第二行\ttab";
            AtomResult run = run(nr, Map.of("commit", "abc123", "changelog", nasty, "description", "say \"hi\"",
                    "user", "ci-bot", "groupId", "grp-1", "deploymentType", "BLUE_GREEN",
                    "deepLink", "https://ci.example.com/42", "timestampSeconds", "1699999999"));

            ok(run);
            Record request = nr.next();
            Map<String, Object> envelope = Json.parseObject(request.bodyText);
            String query = String.valueOf(envelope.get("query"));

            Map<String, String> expectedFields = new LinkedHashMap<>();
            expectedFields.put("version", "1.2.3");
            expectedFields.put("entityGuid", GUID);
            expectedFields.put("commit", "abc123");
            expectedFields.put("changelog", nasty);
            expectedFields.put("description", "say \"hi\"");
            expectedFields.put("user", "ci-bot");
            expectedFields.put("groupId", "grp-1");
            expectedFields.put("deploymentType", "BLUE_GREEN");
            expectedFields.put("deepLink", "https://ci.example.com/42");
            assertEquals(expectedFields, gqlFields(query), "every field survives quoting/escaping");
            assertEquals(1699999999L, gqlTimestamp(query), "timestamp kept");

            List<String[]> order = new ArrayList<>();
            expectedFields.forEach((k, v) -> order.add(new String[] {k, v}));
            assertEquals(expectedGraphql(order, 1699999999L), query, "field order is the documented one");
            assertTrue(query.contains("\\\"2.0\\\""), "quotes inside a value are escaped, not dropped");
            assertEquals("dep-002", data(run, "deploymentId"));
        }
    }

    @Test
    @DisplayName("graphql errors in the response are reported with the first message")
    void graphqlErrors() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(200, "{\"errors\":[{\"message\":\"Invalid entity GUID\",\"extensions\":{\"code\":\"INVALID\"}}]}");
            AtomResult run = run(nr, Map.of());

            refused(run);
            assertEquals("40005", run.field("errorCode"));
            assertTrue(run.field("message").contains("Invalid entity GUID"), run.field("message"));
        }
    }

    @Test
    @DisplayName("graphql without a deploymentId is an error")
    void graphqlWithoutId() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(200, "{\"data\":{\"changeTrackingCreateDeployment\":null}}");
            AtomResult run = run(nr, Map.of());

            refused(run);
            assertEquals("40007", run.field("errorCode"));
        }
    }

    @Test
    @DisplayName("401 and 403 are an authentication problem")
    void authRejected() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            for (int status : List.of(401, 403)) {
                nr.clear();
                nr.push(status, "{\"error\":\"bad key\"}");
                AtomResult run = run(nr, Map.of());

                assertEquals("40006", run.field("errorCode"), "status " + status);
                refused(run);
            }
        }
    }

    @Test
    @DisplayName("429 surfaces Retry-After")
    void rateLimited() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(429, "{\"error\":\"slow down\"}", Map.of("Retry-After", "7"));
            AtomResult run = run(nr, Map.of());

            assertEquals("40004", run.field("errorCode"), "429");
            assertTrue(run.field("message").contains("Retry-After: 7"), run.field("message"));
        }
    }

    @Test
    @DisplayName("404 is reported as not found")
    void notFound() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(404, "{\"error\":\"not found\"}");
            assertEquals("40003", run(nr, Map.of()).field("errorCode"), "404");
        }
    }

    @Test
    @DisplayName("503 reports the status and a body snippet")
    void serverError() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(503, "upstream down");
            AtomResult run = run(nr, Map.of());

            assertEquals("40005", run.field("errorCode"), "503");
            assertTrue(run.field("message").contains("503") && run.field("message").contains("upstream down"),
                    run.field("message"));
        }
    }

    @Test
    @DisplayName("a non-JSON body is reported as such")
    void nonJson() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(200, "<html>gateway</html>");
            assertEquals("40008", run(nr, Map.of()).field("errorCode"), "non-json");
        }
    }

    @Test
    @DisplayName("a redirect is not followed, so the API key is never re-sent")
    void redirectIsNotFollowed() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(302, "", Map.of("Location", "http://elsewhere.invalid/graphql"));
            AtomResult run = run(nr, Map.of());

            assertEquals("40005", run.field("errorCode"), "302: reported as unexpected status");
            Record first = nr.next();
            assertTrue(first != null && "/graphql".equals(first.path), "the first hop is the only hop");
            assertEquals(0, nr.count(), "the API key is not re-sent to the redirect target");
        }
    }

    @Test
    @DisplayName("v2: the path carries the application id and the body is {deployment:{...}}")
    void v2Minimal() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(200, "{\"deployment\":{\"id\":987654321,\"revision\":\"9.9.9\"}}");
            AtomResult run = run(nr, Map.of("mode", "v2", "applicationId", "12345", "version", "9.9.9",
                    "changelog", "built by CI", "user", "jenkins"));

            ok(run);
            Record request = nr.next();
            assertEquals("/v2/applications/12345/deployments.json", request.path, "path carries the application id");
            assertEquals(Json.parseObject("{\"deployment\": {\"revision\": \"9.9.9\", \"changelog\": \"built by CI\","
                    + " \"user\": \"jenkins\"}}"), Json.parse(request.bodyText), "v2 body");
            assertEquals(KEY, request.headers.get("x-api-key"), "X-Api-Key header");
            assertEquals("987654321", data(run, "deploymentId"), "numeric id reported as a string");
        }
    }

    @Test
    @DisplayName("v2: a 201 is a success and reports its id")
    void v2Created() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(201, "{\"deployment\":{\"id\":42,\"revision\":\"1.0.0\"}}");
            AtomResult run = run(nr, Map.of("mode", "v2", "applicationId", "7"));

            ok(run);
            assertEquals("201", data(run, "httpCode"));
            assertEquals("42", data(run, "deploymentId"));
        }
    }

    @Test
    @DisplayName("v2: 404 for an unknown application")
    void v2NotFound() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(404, "{\"error\":{\"title\":\"application not found\"}}");
            assertEquals("40003", run(nr, Map.of("mode", "v2", "applicationId", "999")).field("errorCode"), "v2 404");
        }
    }

    @Test
    @DisplayName("missing or contradictory inputs are refused before anything is sent")
    void missingInputs() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            record Case(Map<String, String> overrides, String label) {}

            List<Case> cases = List.of(
                    new Case(Map.of("apiKey", ""), "no apiKey"),
                    new Case(Map.of("entityGuid", ""), "graphql without entityGuid"),
                    new Case(Map.of("mode", "v2", "applicationId", ""), "v2 without applicationId"),
                    new Case(Map.of("mode", "v2", "applicationId", "abc"), "v2 with a non-numeric applicationId"),
                    new Case(Map.of("region", "mars"), "unknown region"),
                    new Case(Map.of("mode", "soap"), "unknown mode"),
                    new Case(Map.of("version", ""), "no version"),
                    new Case(Map.of("timestampSeconds", "yesterday"), "unparsable timestamp"),
                    new Case(Map.of("timestampSeconds", "-5"), "negative timestamp"));

            for (Case one : cases) {
                nr.clear();
                AtomResult run = run(nr, one.overrides());
                assertEquals("40001", run.field("errorCode"), one.label() + ": errorCode 40001");
                assertEquals(0, nr.count(), one.label() + ": nothing was sent");
            }
        }
    }

    @Test
    @DisplayName("a request that outlives timeoutSeconds is reported as a timeout")
    void timeout() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(200, "{\"data\":{\"changeTrackingCreateDeployment\":{\"deploymentId\":\"late\"}}}");
            nr.delay = 2500;
            AtomResult run = run(nr, Map.of("timeoutSeconds", "1"));

            assertEquals("40009", run.field("errorCode"), "timeout");
        }
    }

    @Test
    @DisplayName("an unreachable host is reported as a connection problem")
    void unreachable() throws Exception {
        Map<String, String> inputs = baseInputs(1);
        inputs.put("baseUrl", "http://127.0.0.1:1");
        assertEquals("40002", run(atom, inputs).field("errorCode"), "unreachable");
    }

    @Test
    @DisplayName("a baseUrl without a scheme is refused")
    void baseUrlWithoutScheme() throws Exception {
        Map<String, String> inputs = baseInputs(1);
        inputs.put("baseUrl", "api.newrelic.com");
        assertEquals("40002", run(atom, inputs).field("errorCode"), "baseUrl without scheme");
    }

    @Test
    @DisplayName("the API key may come from a bound secret when apiKey is empty")
    void usesTheSecretPath() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(200, "{\"data\":{\"changeTrackingCreateDeployment\":{\"deploymentId\":\"dep-from-secret\"}}}");
            Map<String, String> inputs = baseInputs(nr.port());
            inputs.put("apiKey", "");

            AtomResult run = atom.file(".secrets.json", "{\"apiKey\": \"NRAK-FROM-SECRETS\"}")
                    .input(Json.write(inputs))
                    .run();

            ok(run);
            Record request = nr.next();
            assertTrue(request != null, "a request reached the server");
            assertEquals("NRAK-FROM-SECRETS", request.headers.get("x-api-key"),
                    "the bound secret is used when apiKey is empty");
            assertEquals("dep-from-secret", data(run, "deploymentId"), "success reported");
        }
    }

    @Test
    @DisplayName("the API key never appears in output.json or the console")
    void keyNeverLeaks() throws Exception {
        try (NewRelic nr = NewRelic.start()) {
            nr.push(500, "{\"error\":\"boom\"}");
            AtomResult run = run(nr, Map.of());

            String serialized = run.file("output.json") + run.console();
            assertFalse(serialized.contains(KEY), "key never appears in output.json or the log");
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private AtomResult run(NewRelic nr, Map<String, String> overrides) throws Exception {
        return run(atom, baseInputs(nr.port(), overrides));
    }

    private static AtomResult run(Atom target, Map<String, String> inputs) throws Exception {
        return target.input(Json.write(inputs)).run();
    }

    private static Map<String, String> baseInputs(int port) {
        return baseInputs(port, Map.of());
    }

    private static Map<String, String> baseInputs(int port, Map<String, String> overrides) {
        Map<String, String> inputs = new LinkedHashMap<>();
        inputs.put("apiKey", KEY);
        inputs.put("region", "us");
        inputs.put("baseUrl", "http://127.0.0.1:" + port);
        inputs.put("mode", "graphql");
        inputs.put("entityGuid", GUID);
        inputs.put("applicationId", "");
        inputs.put("version", "1.2.3");
        inputs.put("commit", "");
        inputs.put("changelog", "");
        inputs.put("description", "");
        inputs.put("user", "");
        inputs.put("groupId", "");
        inputs.put("deploymentType", "");
        inputs.put("deepLink", "");
        inputs.put("timestampSeconds", "");
        inputs.put("timeoutSeconds", "10");
        inputs.putAll(overrides);
        return inputs;
    }

    private static void ok(AtomResult run) {
        assertEquals("success", run.status(), run.file("output.json") + "\nconsole:\n" + run.console());
        run.assertOk();
    }

    private static void refused(AtomResult run) {
        assertNotEquals(0, run.exit(), "a refused request must not exit 0");
        run.assertRefused();
        assertTrue(List.of("failure", "error").contains(run.status()), "status is a failure, got " + run.status());
    }

    private static String data(AtomResult run, String key) {
        Object data = Json.parseObject(run.file("output.json")).get("data");
        assertInstanceOf(Map.class, data, "output.json:\n" + run.file("output.json"));
        Object node = ((Map<?, ?>) data).get(key);
        assertInstanceOf(Map.class, node, "no output '" + key + "'");
        return String.valueOf(((Map<?, ?>) node).get("value"));
    }

    /** The document the atom promises, rebuilt from the field list: order, quoting and escaping. */
    private static String expectedGraphql(List<String[]> fields, Long timestamp) {
        List<String> parts = new ArrayList<>();
        for (String[] field : fields) {
            parts.add(field[0] + ": \"" + gqlEscape(field[1]) + "\"");
        }
        if (timestamp != null) {
            parts.add("timestamp: " + timestamp);
        }
        return "mutation {changeTrackingCreateDeployment(deployment: {" + String.join(", ", parts)
                + "}) {deploymentId}}";
    }

    private static String gqlEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** The deployment object, parsed back: field -> value. Every element must be understood, or this fails. */
    private static Map<String, String> gqlFields(String query) {
        Matcher deployment = DEPLOYMENT.matcher(query);
        assertTrue(deployment.find(), "the document is not the expected mutation: " + query);
        String body = deployment.group(1);
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = FIELD.matcher(body);
        int consumed = 0;
        while (matcher.find()) {
            consumed++;
            if (matcher.group(2) != null) {
                fields.put(matcher.group(1), gqlUnescape(matcher.group(2)));
            }
        }
        int pieces = body.split(", (?=[A-Za-z_]+\\s*:)", -1).length;
        assertEquals(pieces, consumed, "the document has elements that do not parse: " + body);
        return fields;
    }

    private static Long gqlTimestamp(String query) {
        Matcher deployment = DEPLOYMENT.matcher(query);
        assertTrue(deployment.find(), "the document is not the expected mutation: " + query);
        Matcher matcher = FIELD.matcher(deployment.group(1));
        while (matcher.find()) {
            if (matcher.group(2) == null) {
                return Long.parseLong(matcher.group(3));
            }
        }
        return null;
    }

    private static String gqlUnescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                out.append(switch (next) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> next;
                });
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** One recorded POST. */
    private record Record(String path, Map<String, String> headers, String bodyText) {}

    private record Queued(int status, String body, Map<String, String> headers) {}

    /** A fake New Relic: records every request and answers from a queue the case installs. */
    private static final class NewRelic implements AutoCloseable {

        private final HttpServer server;
        private final List<Record> requests = new ArrayList<>();
        private final Deque<Queued> responses = new ArrayDeque<>();
        private volatile long delay;

        private NewRelic(HttpServer server) {
            this.server = server;
        }

        static NewRelic start() throws IOException {
            NewRelic nr = new NewRelic(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
            nr.server.createContext("/", nr::handle);
            nr.server.start();
            return nr;
        }

        int port() {
            return server.getAddress().getPort();
        }

        void push(int status, String body) {
            push(status, body, Map.of());
        }

        synchronized void push(int status, String body, Map<String, String> headers) {
            responses.add(new Queued(status, body, headers));
        }

        synchronized void clear() {
            requests.clear();
            responses.clear();
        }

        synchronized Record next() {
            return requests.isEmpty() ? null : requests.remove(0);
        }

        synchronized int count() {
            return requests.size();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> headers = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) ->
                    headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            synchronized (this) {
                requests.add(new Record(exchange.getRequestURI().getPath(), headers, body));
            }
            Queued answer;
            synchronized (this) {
                answer = responses.isEmpty()
                        ? new Queued(500, "no response queued", Map.of())
                        : responses.removeFirst();
            }
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] payload = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            answer.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(answer.status(), payload.length == 0 ? -1 : payload.length);
            if (payload.length > 0) {
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
