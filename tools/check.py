#!/usr/bin/env python3
"""Behavioural check for the newrelic-deployment atom.

A real HTTP server stands in for New Relic: it records every request (method, path, headers, raw body) and answers
from a queue the case installs. Every expectation is recomputed here, from the case's inputs and from the documented
protocol - the GraphQL document is re-derived (escaping rules and field order) *and* parsed back field by field, the
v2 body is compared as a JSON document, the API key is compared against what the case put in, and the atom's
output.json is read for status/errorCode/data.

Run: python3 tools/check.py [--verbose]
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
JAR = os.path.join(REPO, "dist", "newrelic-deployment", "newrelic-deployment.jar")
JAVA = os.path.join(os.environ.get("JAVA_HOME", ""), "bin", "java") if os.environ.get("JAVA_HOME") else "java"

VERBOSE = "--verbose" in sys.argv
failures = []
checks = 0


def check(condition, message):
    global checks
    checks += 1
    if not condition:
        failures.append(message)
        print("FAIL: " + message)
    elif VERBOSE:
        print("ok: " + message)


class Recorder:
    def __init__(self):
        self.requests = []
        self.responses = []
        self.delay = 0.0
        self.lock = threading.Lock()

    def push(self, status, body=b"", headers=None):
        with self.lock:
            self.responses.append((status, body, headers or {}))

    def next_request(self):
        with self.lock:
            return self.requests.pop(0) if self.requests else None

    def count(self):
        with self.lock:
            return len(self.requests)


RECORDER = Recorder()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # keep the test output clean
        if VERBOSE:
            sys.stderr.write("server: " + (fmt % args) + "\n")

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        with RECORDER.lock:
            RECORDER.requests.append({
                "method": "POST",
                "path": self.path,
                "headers": {k.lower(): v for k, v in self.headers.items()},
                "body": body,
                "bodyText": body.decode("utf-8", "replace"),
            })
            status, payload, extra = RECORDER.responses.pop(0) if RECORDER.responses else (500, b"no response queued", {})
        if RECORDER.delay:
            time.sleep(RECORDER.delay)
        self.send_response(status)
        headers = {"Content-Type": "application/json"}
        headers.update(extra)
        for key, value in headers.items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except BrokenPipeError:
            pass

    def do_GET(self):
        self.send_response(405)
        self.send_header("Content-Length", "0")
        self.end_headers()


def start_server():
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    return server, server.server_address[1]


def run_atom(inputs, secrets=None):
    directory = tempfile.mkdtemp(prefix="nr-check-")
    try:
        data = dict(inputs)
        data["atom_workspace"] = directory
        with open(os.path.join(directory, "input.json"), "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False)
        if secrets:
            with open(os.path.join(directory, ".secrets.json"), "w", encoding="utf-8") as handle:
                json.dump(secrets, handle)
        env = dict(os.environ)
        env.update({
            "atom_data_dir": directory,
            "atom_data_input": "input.json",
            "atom_data_output": "output.json",
            "atom_workspace": directory,
        })
        proc = subprocess.run([JAVA, "-cp", JAR, "com.jenkinschina.atom.sdk.AtomRunner"],
                              cwd=directory, env=env, capture_output=True, text=True, timeout=120)
        output_path = os.path.join(directory, "output.json")
        document = json.load(open(output_path, encoding="utf-8")) if os.path.exists(output_path) else {}
        return proc, document
    finally:
        shutil.rmtree(directory, ignore_errors=True)


def data_of(document):
    return {k: v.get("value") if isinstance(v, dict) else v for k, v in document.get("data", {}).items()} \
        if isinstance(document.get("data"), dict) else {}


def gql_escape(value):
    return value.replace("\\", "\\\\").replace('"', '\\"')


def gql_unescape(value):
    out = []
    i = 0
    while i < len(value):
        c = value[i]
        if c == "\\" and i + 1 < len(value):
            nxt = value[i + 1]
            out.append({"n": "\n", "r": "\r", "t": "\t", '"': '"', "\\": "\\"}.get(nxt, nxt))
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def expected_graphql(fields, timestamp=None):
    """The document the atom promises, rebuilt here from the field list: order, quoting and escaping."""
    parts = []
    for key, value in fields:
        parts.append('%s: "%s"' % (key, gql_escape(value)))
    if timestamp is not None:
        parts.append("timestamp: %d" % timestamp)
    return "mutation {changeTrackingCreateDeployment(deployment: {%s}) {deploymentId}}" % ", ".join(parts)


def graphql_fields(query):
    """Parse back the deployment object: a dict of field -> value plus the numeric timestamp."""
    match = re.search(r"changeTrackingCreateDeployment\(deployment: \{(.*)\}\) \{deploymentId\}", query, re.S)
    if not match:
        return None, None
    body = match.group(1)
    fields = {}
    timestamp = None
    pattern = re.compile(r'([A-Za-z_][A-Za-z0-9_]*): (?:"((?:[^"\\]|\\.)*)"|(-?[0-9]+))')
    consumed = 0
    for found in pattern.finditer(body):
        consumed += 1
        name = found.group(1)
        if found.group(2) is not None:
            fields[name] = gql_unescape(found.group(2))
        else:
            timestamp = int(found.group(3))
    # every comma-separated element must have been understood, otherwise the document is malformed
    pieces = [p for p in re.split(r', (?=[A-Za-z_]+\s*:)', body) if p.strip()]
    if consumed != len(pieces):
        return None, None
    return fields, timestamp


def base_inputs(port, **overrides):
    inputs = {
        "apiKey": "NRAK-SECRET-KEY-1234",
        "region": "us",
        "baseUrl": "http://127.0.0.1:%d" % port,
        "mode": "graphql",
        "entityGuid": "MXxBUE18QVBQTElDQVRJT058MTIzNDU2Nzg5",
        "applicationId": "",
        "version": "1.2.3",
        "commit": "",
        "changelog": "",
        "description": "",
        "user": "",
        "groupId": "",
        "deploymentType": "",
        "deepLink": "",
        "timestampSeconds": "",
        "timeoutSeconds": "10",
    }
    inputs.update(overrides)
    return inputs


def main():
    if not os.path.exists(JAR):
        print("missing %s - run ./build.sh first" % JAR)
        return 2
    server, port = start_server()
    print("fake New Relic on 127.0.0.1:%d" % port)
    try:
        case_graphql_minimal(port)
        case_graphql_default_timestamp(port)
        case_graphql_full_escaping(port)
        case_graphql_errors(port)
        case_graphql_no_id(port)
        case_auth_rejected(port)
        case_rate_limited(port)
        case_not_found(port)
        case_server_error(port)
        case_non_json(port)
        case_redirect_not_followed(port)
        case_v2_minimal(port)
        case_v2_created(port)
        case_v2_not_found(port)
        case_missing_inputs(port)
        case_timeout(port)
        case_unreachable()
        case_base_url_not_http()
        case_secret_path(port)
        case_no_key_in_output(port)
    finally:
        server.shutdown()
        server.server_close()

    print("\n%d checks, %d failed" % (checks, len(failures)))
    if failures:
        for failure in failures:
            print(" - " + failure)
        return 1
    print("ALL GREEN")
    return 0


def case_graphql_minimal(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(200, b'{"data":{"changeTrackingCreateDeployment":{"deploymentId":"dep-001"}}}')
    proc, doc = run_atom(base_inputs(port, timestampSeconds="1700000000"))
    request = RECORDER.next_request()

    check(proc.returncode == 0, "graphql minimal: runner exit 0 (got %d)" % proc.returncode)
    check(doc.get("status") == "success", "graphql minimal: status success (got %r)" % doc.get("status"))
    check(request is not None, "graphql minimal: one request reached the server")
    if request is None:
        return
    check(request["path"] == "/graphql", "graphql minimal: POST /graphql (got %s)" % request["path"])
    check(request["headers"].get("x-api-key") == "NRAK-SECRET-KEY-1234",
          "graphql minimal: X-Api-Key is the job's key (got %r)" % request["headers"].get("x-api-key"))
    check(request["headers"].get("content-type", "").startswith("application/json"),
          "graphql minimal: Content-Type json (got %r)" % request["headers"].get("content-type"))
    envelope = json.loads(request["bodyText"])
    query = envelope.get("query", "")
    fields, timestamp = graphql_fields(query)
    check(fields == {"version": "1.2.3", "entityGuid": "MXxBUE18QVBQTElDQVRJT058MTIzNDU2Nzg5"},
          "graphql minimal: the document carries version and entityGuid only (got %r)" % (fields,))
    check(timestamp == 1700000000, "graphql minimal: timestamp is the given Unix second (got %r)" % timestamp)
    expected = expected_graphql([("version", "1.2.3"),
                                 ("entityGuid", "MXxBUE18QVBQTElDQVRJT058MTIzNDU2Nzg5")], 1700000000)
    check(query == expected, "graphql minimal: document matches the rebuilt one\n  got %s\n  exp %s" % (query, expected))
    check(list(envelope.keys()) == ["query"], "graphql minimal: envelope has only the query key (got %r)" % list(envelope))
    check(data_of(doc).get("deploymentId") == "dep-001",
          "graphql minimal: deploymentId read back (got %r)" % data_of(doc).get("deploymentId"))
    check(data_of(doc).get("httpCode") == "200", "graphql minimal: httpCode 200")
    check(RECORDER.count() == 0, "graphql minimal: exactly one request")


def case_graphql_default_timestamp(port):
    """Empty timestampSeconds means "let New Relic stamp it", i.e. the field is ABSENT - never 0, never millis."""
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(200, b'{"data":{"changeTrackingCreateDeployment":{"deploymentId":"dep-000"}}}')
    proc, doc = run_atom(base_inputs(port))
    request = RECORDER.next_request()

    check(proc.returncode == 0, "default timestamp: runner exit 0 (got %d)" % proc.returncode)
    check(request is not None, "default timestamp: one request reached the server")
    if request is None:
        return
    query = json.loads(request["bodyText"]).get("query", "")
    fields, timestamp = graphql_fields(query)
    check(fields == {"version": "1.2.3", "entityGuid": "MXxBUE18QVBQTElDQVRJT058MTIzNDU2Nzg5"},
          "default timestamp: only version and entityGuid (got %r)" % (fields,))
    check(timestamp is None, "default timestamp: no timestamp field at all (got %r)" % (timestamp,))
    check("timestamp" not in query, "default timestamp: the word timestamp is absent (got %s)" % query)
    expected = expected_graphql([("version", "1.2.3"),
                                 ("entityGuid", "MXxBUE18QVBQTElDQVRJT058MTIzNDU2Nzg5")])
    check(query == expected, "default timestamp: document matches the rebuilt one\n  got %s\n  exp %s" % (query, expected))
    check(RECORDER.count() == 0, "default timestamp: exactly one request")


def case_graphql_full_escaping(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(200, b'{"data":{"changeTrackingCreateDeployment":{"deploymentId":"dep-002"}}}')
    nasty = 'release "2.0" \\ 中文\n第二行\ttab'
    inputs = base_inputs(port, commit="abc123", changelog=nasty, description='say "hi"', user="ci-bot",
                         groupId="grp-1", deploymentType="BLUE_GREEN", deepLink="https://ci.example.com/42",
                         timestampSeconds="1699999999")
    proc, doc = run_atom(inputs)
    request = RECORDER.next_request()
    check(proc.returncode == 0, "graphql full: runner exit 0 (got %d, %s)" % (proc.returncode, proc.stdout[-300:]))
    if request is None:
        check(False, "graphql full: request reached the server")
        return
    query = json.loads(request["bodyText"]).get("query", "")
    fields, timestamp = graphql_fields(query)
    expected_fields = {
        "version": "1.2.3",
        "entityGuid": "MXxBUE18QVBQTElDQVRJT058MTIzNDU2Nzg5",
        "commit": "abc123",
        "changelog": nasty,
        "description": 'say "hi"',
        "user": "ci-bot",
        "groupId": "grp-1",
        "deploymentType": "BLUE_GREEN",
        "deepLink": "https://ci.example.com/42",
    }
    check(fields == expected_fields, "graphql full: every field survives quoting/escaping\n  got %r\n  exp %r"
          % (fields, expected_fields))
    check(timestamp == 1699999999, "graphql full: timestamp kept (got %r)" % timestamp)
    order = [("version", "1.2.3"), ("entityGuid", "MXxBUE18QVBQTElDQVRJT058MTIzNDU2Nzg5"),
             ("commit", "abc123"), ("changelog", nasty), ("description", 'say "hi"'), ("user", "ci-bot"),
             ("groupId", "grp-1"), ("deploymentType", "BLUE_GREEN"), ("deepLink", "https://ci.example.com/42")]
    check(query == expected_graphql(order, 1699999999), "graphql full: field order is the documented one")
    check('\\"2.0\\"' in query, "graphql full: quotes inside a value are escaped, not dropped")
    check(data_of(doc).get("deploymentId") == "dep-002", "graphql full: deploymentId read back")


def case_graphql_errors(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(200, b'{"errors":[{"message":"Invalid entity GUID","extensions":{"code":"INVALID"}}]}')
    proc, doc = run_atom(base_inputs(port))
    check(proc.returncode != 0, "graphql errors: non-zero exit")
    check(doc.get("status") in ("failure", "error"), "graphql errors: status is a failure (got %r)" % doc.get("status"))
    check(doc.get("errorCode") == 40005, "graphql errors: errorCode 40005 (got %r)" % doc.get("errorCode"))
    check("Invalid entity GUID" in (doc.get("message") or ""),
          "graphql errors: the first message is reported (got %r)" % doc.get("message"))


def case_graphql_no_id(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(200, b'{"data":{"changeTrackingCreateDeployment":null}}')
    proc, doc = run_atom(base_inputs(port))
    check(doc.get("status") in ("failure", "error"), "graphql no id: status is a failure (got %r)" % doc.get("status"))
    check(doc.get("errorCode") == 40007, "graphql no id: errorCode 40007 (got %r)" % doc.get("errorCode"))


def case_auth_rejected(port):
    for status, expected in ((401, 40006), (403, 40006)):
        RECORDER.requests.clear()
        RECORDER.responses.clear()
        RECORDER.push(status, b'{"error":"bad key"}')
        proc, doc = run_atom(base_inputs(port))
        check(doc.get("errorCode") == expected, "status %d: errorCode %d (got %r)" % (status, expected, doc.get("errorCode")))
        check(doc.get("status") in ("failure", "error"), "status %d: status is a failure (got %r)" % (status, doc.get("status")))


def case_rate_limited(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(429, b'{"error":"slow down"}', {"Retry-After": "7"})
    proc, doc = run_atom(base_inputs(port))
    message = doc.get("message") or ""
    check(doc.get("errorCode") == 40004, "429: errorCode 40004 (got %r)" % doc.get("errorCode"))
    check("Retry-After: 7" in message, "429: Retry-After surfaced (got %r)" % message)


def case_not_found(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(404, b'{"error":"not found"}')
    proc, doc = run_atom(base_inputs(port))
    check(doc.get("errorCode") == 40003, "404: errorCode 40003 (got %r)" % doc.get("errorCode"))


def case_server_error(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(503, b"upstream down")
    proc, doc = run_atom(base_inputs(port))
    message = doc.get("message") or ""
    check(doc.get("errorCode") == 40005, "503: errorCode 40005 (got %r)" % doc.get("errorCode"))
    check("503" in message and "upstream down" in message, "503: status and body snippet reported (got %r)" % message)


def case_non_json(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(200, b"<html>gateway</html>")
    proc, doc = run_atom(base_inputs(port))
    check(doc.get("errorCode") == 40008, "non-json: errorCode 40008 (got %r)" % doc.get("errorCode"))


def case_redirect_not_followed(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(302, b"", {"Location": "http://elsewhere.invalid/graphql"})
    proc, doc = run_atom(base_inputs(port))
    check(doc.get("errorCode") == 40005, "302: reported as unexpected status (got %r)" % doc.get("errorCode"))
    first = RECORDER.next_request()
    check(first is not None and first["path"] == "/graphql", "302: the first hop is the only hop")
    check(RECORDER.count() == 0, "302: the API key is not re-sent to the redirect target")


def case_v2_minimal(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(200, b'{"deployment":{"id":987654321,"revision":"9.9.9"}}')
    inputs = base_inputs(port, mode="v2", applicationId="12345", version="9.9.9",
                         changelog="built by CI", user="jenkins")
    proc, doc = run_atom(inputs)
    request = RECORDER.next_request()
    check(proc.returncode == 0, "v2: runner exit 0 (got %d, %s)" % (proc.returncode, proc.stdout[-300:]))
    if request is None:
        check(False, "v2: request reached the server")
        return
    check(request["path"] == "/v2/applications/12345/deployments.json",
          "v2: path carries the application id (got %s)" % request["path"])
    body = json.loads(request["bodyText"])
    check(body == {"deployment": {"revision": "9.9.9", "changelog": "built by CI", "user": "jenkins"}},
          "v2: body is {deployment:{revision,changelog,user}} (got %r)" % body)
    check(request["headers"].get("x-api-key") == "NRAK-SECRET-KEY-1234", "v2: X-Api-Key header")
    check(data_of(doc).get("deploymentId") == "987654321",
          "v2: numeric id reported as a string (got %r)" % data_of(doc).get("deploymentId"))


def case_v2_created(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(201, b'{"deployment":{"id":42,"revision":"1.0.0"}}')
    proc, doc = run_atom(base_inputs(port, mode="v2", applicationId="7"))
    check(doc.get("status") == "success", "v2 201: success")
    check(data_of(doc).get("httpCode") == "201", "v2 201: httpCode 201 (got %r)" % data_of(doc).get("httpCode"))
    check(data_of(doc).get("deploymentId") == "42", "v2 201: deploymentId 42")


def case_v2_not_found(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(404, b'{"error":{"title":"application not found"}}')
    proc, doc = run_atom(base_inputs(port, mode="v2", applicationId="999"))
    check(doc.get("errorCode") == 40003, "v2 404: errorCode 40003 (got %r)" % doc.get("errorCode"))


def case_missing_inputs(port):
    cases = [
        ({"apiKey": ""}, 40001, "no apiKey"),
        ({"baseUrl": "http://127.0.0.1:%d" % port, "mode": "graphql", "entityGuid": ""}, 40001, "graphql without entityGuid"),
        ({"mode": "v2", "applicationId": ""}, 40001, "v2 without applicationId"),
        ({"mode": "v2", "applicationId": "abc"}, 40001, "v2 with a non-numeric applicationId"),
        ({"region": "mars"}, 40001, "unknown region"),
        ({"mode": "soap"}, 40001, "unknown mode"),
        ({"version": ""}, 40001, "no version"),
        ({"timestampSeconds": "yesterday"}, 40001, "unparsable timestamp"),
        ({"timestampSeconds": "-5"}, 40001, "negative timestamp"),
    ]
    for overrides, expected, label in cases:
        RECORDER.requests.clear()
        RECORDER.responses.clear()
        proc, doc = run_atom(base_inputs(port, **overrides))
        check(doc.get("errorCode") == expected, "%s: errorCode %d (got %r)" % (label, expected, doc.get("errorCode")))
        check(RECORDER.count() == 0, "%s: nothing was sent" % label)


def case_timeout(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(200, b'{"data":{"changeTrackingCreateDeployment":{"deploymentId":"late"}}}')
    RECORDER.delay = 2.5
    try:
        proc, doc = run_atom(base_inputs(port, timeoutSeconds="1"))
    finally:
        RECORDER.delay = 0.0
    check(doc.get("errorCode") == 40009, "timeout: errorCode 40009 (got %r)" % doc.get("errorCode"))


def case_unreachable():
    inputs = base_inputs(1, baseUrl="http://127.0.0.1:1")
    proc, doc = run_atom(inputs)
    check(doc.get("errorCode") == 40002, "unreachable: errorCode 40002 (got %r)" % doc.get("errorCode"))


def case_base_url_not_http():
    proc, doc = run_atom(base_inputs(1, baseUrl="api.newrelic.com"))
    check(doc.get("errorCode") == 40002, "baseUrl without scheme: errorCode 40002 (got %r)" % doc.get("errorCode"))


def case_secret_path(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(200, b'{"data":{"changeTrackingCreateDeployment":{"deploymentId":"dep-from-secret"}}}')
    inputs = base_inputs(port, apiKey="")
    proc, doc = run_atom(inputs, secrets={"apiKey": "NRAK-FROM-SECRETS"})
    request = RECORDER.next_request()
    check(proc.returncode == 0, "secrets: runner exit 0")
    if request is not None:
        check(request["headers"].get("x-api-key") == "NRAK-FROM-SECRETS",
              "secrets: the bound secret is used when apiKey is empty (got %r)" % request["headers"].get("x-api-key"))
    check(data_of(doc).get("deploymentId") == "dep-from-secret", "secrets: success reported")


def case_no_key_in_output(port):
    RECORDER.requests.clear()
    RECORDER.responses.clear()
    RECORDER.push(500, b'{"error":"boom"}')
    proc, doc = run_atom(base_inputs(port))
    serialized = json.dumps(doc, ensure_ascii=False) + proc.stdout + proc.stderr
    check("NRAK-SECRET-KEY-1234" not in serialized, "key never appears in output.json or the log")


if __name__ == "__main__":
    sys.exit(main())
