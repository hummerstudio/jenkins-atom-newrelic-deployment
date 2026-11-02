package com.jenkinschina.atom.newrelicdeployment;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records a deployment marker in New Relic.
 *
 * <p>Port of <em>newrelic-deployment-notifier</em> 1.13 ({@code NewRelicDeploymentNotifier}). The plugin has two
 * transports and this atom keeps both, because which one a job can use depends on the New Relic account:
 *
 * <ul>
 *   <li>{@code mode=graphql} — the NerdGraph change-tracking mutation
 *       {@code changeTrackingCreateDeployment} at {@code https://api[.eu].newrelic.com/graphql}, authenticated with
 *       an {@code X-Api-Key} user key and keyed by an entity GUID (the path the plugin uses when a GUID is given);</li>
 *   <li>{@code mode=v2} — the older REST endpoint
 *       {@code /v2/applications/{applicationId}/deployments.json} with the same {@code X-Api-Key}, keyed by an
 *       application id (the path the plugin uses for an APM application picked by name).</li>
 * </ul>
 *
 * <p>Deliberate differences, all asserted by {@code tools/check.py}: the GraphQL document is assembled from the
 * inputs with a deterministic field order and full JSON escaping (the plugin concatenates the fields and only escapes
 * a double quote, so a changelog with a newline or a backslash produces an invalid query); a GraphQL answer carrying
 * {@code errors} is an error that prints the first message instead of a silent success; every non-2xx status is
 * reported with its code, 401/403 is 40006, 404 is 40003, 429 is 40004 with {@code Retry-After}; a 2xx answer without
 * a deployment id is 40007 (the plugin logs "Unable to reach New Relic to record the Deployment Id" and still marks the
 * build green); there are no retries (the plugin retries three times with a one second sleep); the timestamp is an
 * input defaulting to the current time in seconds (the plugin sends {@code System.currentTimeMillis()});
 * {@code applicationId} is not resolved by name (an atom has no New Relic connection to search with).
 */
public class NewRelicDeploymentAtom implements AtomTask {

    static final int MAX_SNIPPET = 400;

    static final String HOST_US = "api.newrelic.com";
    static final String HOST_EU = "api.eu.newrelic.com";

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        String apiKey = firstNonEmpty(context.getParam("apiKey", "").trim(), context.getSecret("apiKey"));
        String region = context.getParam("region", "us").trim().toLowerCase(Locale.ENGLISH);
        String mode = context.getParam("mode", "graphql").trim().toLowerCase(Locale.ENGLISH);
        String entityGuid = context.getParam("entityGuid", "").trim();
        String applicationId = context.getParam("applicationId", "").trim();
        String version = context.getParam("version", "").trim();
        String commit = context.getParam("commit", "").trim();
        String changelog = context.getParam("changelog", "");
        String description = context.getParam("description", "");
        String user = context.getParam("user", "").trim();
        String groupId = context.getParam("groupId", "").trim();
        String deploymentType = context.getParam("deploymentType", "").trim();
        String deepLink = context.getParam("deepLink", "").trim();
        String rawTimestamp = context.getParam("timestampSeconds", "").trim();
        String rawBase = context.getParam("baseUrl", "").trim();
        int timeoutSeconds = Math.max(1, Math.min(context.getIntParam("timeoutSeconds", 60), 600));

        if (apiKey == null || apiKey.isBlank()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "apiKey is required: a New Relic user key with the deployment/change-tracking permission");
        }
        if (!"us".equals(region) && !"eu".equals(region)) {
            return AtomResult.error(ErrorType.USER, 40001, "region must be us or eu; got '" + region + "'");
        }
        if (!"graphql".equals(mode) && !"v2".equals(mode)) {
            return AtomResult.error(ErrorType.USER, 40001, "mode must be graphql or v2; got '" + mode + "'");
        }
        if (version.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "version is required (the revision the deployment marker should carry)");
        }
        if ("graphql".equals(mode) && entityGuid.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001,
                    "entityGuid is required for mode=graphql (New Relic entity GUID of the deployed entity)");
        }
        if ("v2".equals(mode)) {
            if (applicationId.isEmpty()) {
                return AtomResult.error(ErrorType.USER, 40001,
                        "applicationId is required for mode=v2 (the APM application id)");
            }
            if (!applicationId.matches("[0-9]+")) {
                return AtomResult.error(ErrorType.USER, 40001,
                        "applicationId must be the numeric APM application id; got '" + applicationId
                                + "' (an atom cannot search New Relic by name for the id)");
            }
        }

        Long timestamp = null;
        if (!rawTimestamp.isEmpty()) {
            try {
                long parsed = Long.parseLong(rawTimestamp);
                if (parsed < 0) {
                    return AtomResult.error(ErrorType.USER, 40001,
                            "timestampSeconds must not be negative; got " + rawTimestamp);
                }
                timestamp = parsed;
            } catch (NumberFormatException e) {
                return AtomResult.error(ErrorType.USER, 40001,
                        "timestampSeconds must be whole seconds since the epoch; got '" + rawTimestamp + "'");
            }
        }

        String host = "eu".equals(region) ? HOST_EU : HOST_US;
        String base = rawBase.isEmpty() ? "https://" + host : stripTrailingSlash(rawBase);
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            return AtomResult.error(ErrorType.USER, 40002, "baseUrl must be http or https: " + rawBase);
        }
        String endpoint;
        byte[] body;
        try {
            if ("graphql".equals(mode)) {
                endpoint = base + "/graphql";
                body = graphqlBody(version, commit, changelog, description, user, groupId, entityGuid,
                        deploymentType, deepLink, timestamp).getBytes(StandardCharsets.UTF_8);
            } else {
                endpoint = base + "/v2/applications/" + applicationId + "/deployments.json";
                body = v2Body(version, changelog, description, user).getBytes(StandardCharsets.UTF_8);
            }
            URI.create(endpoint);
        } catch (IllegalArgumentException e) {
            return AtomResult.error(ErrorType.USER, 40002, "cannot build a request URL: " + e.getMessage());
        }

        context.log("POST " + endpoint + " (" + body.length + " bytes, mode=" + mode + ", region=" + region + ")");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-Api-Key", apiKey)
                .header("NewRelic-Requesting-Services", "newrelic-jenkins-plugin")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40009,
                    "no answer from " + endpoint + " within " + timeoutSeconds + "s");
        } catch (IOException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40002, "could not reach " + endpoint + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AtomResult.error(ErrorType.SYSTEM, 40002, "interrupted while calling " + endpoint);
        }

        int status = response.statusCode();
        String text = response.body() == null ? "" : response.body();
        String snippet = snippet(text);
        context.log("New Relic returned HTTP/" + status);

        if (status == 401 || status == 403) {
            return AtomResult.error(ErrorType.THIRD_PART, 40006,
                    "the New Relic API key was rejected (HTTP/" + status + ")"
                            + (snippet.isEmpty() ? "" : ": " + snippet));
        }
        if (status == 404) {
            return AtomResult.error(ErrorType.THIRD_PART, 40003,
                    "New Relic has no such " + ("v2".equals(mode) ? "application " + applicationId : "endpoint")
                            + " on " + host + " (HTTP/404) - check region and id"
                            + (snippet.isEmpty() ? "" : ": " + snippet));
        }
        if (status == 429) {
            String retryAfter = response.headers().firstValue("retry-after").orElse("");
            return AtomResult.error(ErrorType.THIRD_PART, 40004,
                    "New Relic rate-limited the call (HTTP/429)"
                            + (retryAfter.isEmpty() ? "" : ", Retry-After: " + retryAfter)
                            + (snippet.isEmpty() ? "" : ": " + snippet));
        }
        if (status < 200 || status >= 300) {
            return AtomResult.error(ErrorType.THIRD_PART, 40005,
                    "unexpected HTTP/" + status + " from " + endpoint
                            + (snippet.isEmpty() ? "" : ": " + snippet));
        }

        Object parsed;
        try {
            parsed = new JsonReader(text).parseDocument();
        } catch (RuntimeException e) {
            return AtomResult.error(ErrorType.THIRD_PART, 40008,
                    "HTTP/" + status + " from " + endpoint + " is not JSON: " + e.getMessage()
                            + (snippet.isEmpty() ? "" : ": " + snippet));
        }

        String graphqlError = firstErrorMessage(parsed);
        if (graphqlError != null) {
            return AtomResult.error(ErrorType.THIRD_PART, 40005,
                    "New Relic rejected the mutation: " + graphqlError);
        }

        String deploymentId = "graphql".equals(mode)
                ? stringAt(parsed, "data", "changeTrackingCreateDeployment", "deploymentId")
                : stringAt(parsed, "deployment", "id");
        if (deploymentId == null || deploymentId.isBlank()) {
            return AtomResult.error(ErrorType.THIRD_PART, 40007,
                    "HTTP/" + status + " from " + endpoint + " carried no deployment id, so the marker was not recorded"
                            + (snippet.isEmpty() ? "" : ": " + snippet));
        }

        context.log("Deployment marker recorded as " + deploymentId);
        return AtomResult.success("newrelic deployment " + deploymentId + " (" + version + ")")
                .data("deploymentId", deploymentId)
                .data("mode", mode)
                .data("region", region)
                .data("endpoint", endpoint)
                .data("httpCode", Integer.toString(status))
                .data("version", version)
                .data("response", snippet);
    }

    /** The NerdGraph document wrapped in the {@code {"query": ...}} envelope the API expects. */
    static String graphqlBody(String version, String commit, String changelog, String description, String user,
                              String groupId, String entityGuid, String deploymentType, String deepLink,
                              Long timestampSeconds) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("version", version);
        fields.put("entityGuid", entityGuid);
        putIfNotEmpty(fields, "commit", commit);
        putIfNotEmpty(fields, "changelog", changelog);
        putIfNotEmpty(fields, "description", description);
        putIfNotEmpty(fields, "user", user);
        putIfNotEmpty(fields, "groupId", groupId);
        putIfNotEmpty(fields, "deploymentType", deploymentType);
        putIfNotEmpty(fields, "deepLink", deepLink);

        StringBuilder deployment = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                deployment.append(", ");
            }
            first = false;
            deployment.append(entry.getKey()).append(": \"").append(escapeGraphql(entry.getValue())).append('"');
        }
        if (timestampSeconds != null) {
            deployment.append(", timestamp: ").append(timestampSeconds);
        }
        String query = "mutation {changeTrackingCreateDeployment(deployment: {" + deployment + "}) {deploymentId}}";
        return "{\"query\":\"" + escapeJson(query) + "\"}";
    }

    /** The legacy REST body: {@code {"deployment":{revision,changelog,description,user}}}. */
    static String v2Body(String revision, String changelog, String description, String user) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("revision", revision);
        putIfNotEmpty(fields, "changelog", changelog);
        putIfNotEmpty(fields, "description", description);
        putIfNotEmpty(fields, "user", user);
        StringBuilder deployment = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                deployment.append(',');
            }
            first = false;
            deployment.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append('"');
        }
        deployment.append('}');
        return "{\"deployment\":" + deployment + "}";
    }

    static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Inside the GraphQL string literal {@code "..."}, both a quote and a backslash have to be escaped. */
    static String escapeGraphql(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void putIfNotEmpty(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static String firstErrorMessage(Object document) {
        if (!(document instanceof Map<?, ?> root)) {
            return null;
        }
        Object errors = root.get("errors");
        if (!(errors instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.get(0);
        if (first instanceof Map<?, ?> map && map.get("message") instanceof String message) {
            return message;
        }
        return String.valueOf(first);
    }

    private static String stringAt(Object document, String... path) {
        Object node = document;
        for (String key : path) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = map.get(key);
        }
        if (node == null) {
            return null;
        }
        if (node instanceof String s) {
            return s;
        }
        if (node instanceof Number n) {
            return n.toString();
        }
        return null;
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_SNIPPET ? flat : flat.substring(0, MAX_SNIPPET) + "...";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        return second == null ? "" : second;
    }
}
