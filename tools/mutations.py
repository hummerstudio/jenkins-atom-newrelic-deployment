#!/usr/bin/env python3
"""Mutation test: every deliberate break of the atom must make tools/check.py fail.

A green check proves nothing until a wrong implementation fails it. Each entry patches one rule of the atom in a
throwaway copy of the repository, builds it, runs the real behavioural check, and requires a non-zero exit. The first
run is an unpatched control that must stay green.
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ATOM = os.path.join(ROOT, "src/main/java/com/jenkinschina/atom/newrelicdeployment/NewRelicDeploymentAtom.java")


def find_sdk():
    candidates = [os.environ.get("SDK_JAR", ""),
                  os.path.join(ROOT, "..", "jenkins-atom-sdk", "target"),
                  os.path.join(ROOT, "jenkins-atom-sdk", "target"),
                  os.path.expanduser("~/Codes/jenkins-atom-sdk/target")]
    for candidate in candidates:
        if candidate and candidate.endswith(".jar") and os.path.exists(candidate):
            return candidate
        found = sorted(glob.glob(os.path.join(candidate, "jenkins-atom-sdk-*.jar"))) if candidate else []
        if found:
            return found[-1]
    raise SystemExit("build the SDK first (../jenkins-atom-sdk/build.sh) or set SDK_JAR")


SDK_JAR = find_sdk()

MUTATIONS = [
    ("the API key header is dropped",
     '.header("X-Api-Key", apiKey)', '.header("X-Api-Key-Dropped", apiKey)'),
    ("the body is sent as text/plain",
     '.header("Content-Type", "application/json")', '.header("Content-Type", "text/plain")'),
    ("version is emitted after entityGuid",
     '        fields.put("version", version);\n        fields.put("entityGuid", entityGuid);',
     '        fields.put("entityGuid", entityGuid);\n        fields.put("version", version);'),
    ("entityGuid is not sent at all",
     '        fields.put("entityGuid", entityGuid);', '        // entityGuid dropped'),
    ("the timestamp is never appended",
     'if (timestampSeconds != null) {', 'if (false) {'),
    ("quotes inside values are not JSON-escaped",
     '                case \'"\' -> out.append("\\\\\\"");', '                case \'"\' -> out.append(\'"\');'),
    ("a backslash is not GraphQL-escaped",
     'value.replace("\\\\", "\\\\\\\\").replace("\\"", "\\\\\\"")', 'value.replace("\\"", "\\\\\\"")'),
    ("the v2 path is the wrong version",
     '"/v2/applications/" + applicationId', '"/v3/applications/" + applicationId'),
    ("401 is reported as a generic error",
     'if (status == 401 || status == 403) {', 'if (status == 4011 || status == 403) {'),
    ("429 is reported as a generic error",
     'if (status == 429) {', 'if (status == 4290) {'),
    ("the graphql id is read from the wrong field",
     'stringAt(parsed, "data", "changeTrackingCreateDeployment", "deploymentId")',
     'stringAt(parsed, "data", "changeTrackingCreateDeployment", "id")'),
    ("a response without an id counts as success",
     'if (deploymentId == null || deploymentId.isBlank()) {', 'if (false) {'),
    ("redirects are followed",
     '.followRedirects(HttpClient.Redirect.NEVER)', '.followRedirects(HttpClient.Redirect.ALWAYS)'),
    ("the v2 body calls the revision version",
     'fields.put("revision", revision);', 'fields.put("version", revision);'),
    ("the timestamp input is ignored when absent",
     'if (timestampSeconds != null) {\n            deployment.append(", timestamp: ").append(timestampSeconds);',
     'if (timestampSeconds != null || true) {\n            deployment.append(", timestamp: ").append(timestampSeconds == null ? 0 : timestampSeconds);'),
]


def prepare():
    directory = tempfile.mkdtemp(prefix="nr-mut-")
    for name in ("src", "tools", "build.sh", "task.json", "meta.json"):
        source = os.path.join(ROOT, name)
        if os.path.isdir(source):
            shutil.copytree(source, os.path.join(directory, name))
        elif os.path.exists(source):
            shutil.copy2(source, os.path.join(directory, name))
    return directory


def run_check(directory):
    env = dict(os.environ)
    env["SDK_JAR"] = SDK_JAR
    env.setdefault("JAVA_HOME", os.path.expanduser("~/tools/jdk-21.0.12.1+1/Contents/Home"))
    subprocess.run(["bash", os.path.join(directory, "build.sh")], cwd=directory, env=env,
                   capture_output=True, text=True, timeout=300, check=False)
    proc = subprocess.run([sys.executable, os.path.join(directory, "tools", "check.py")], cwd=directory, env=env,
                          capture_output=True, text=True, timeout=600)
    return proc.returncode, proc.stdout + proc.stderr


def main():
    directory = prepare()
    try:
        code, output = run_check(directory)
        if code != 0:
            print("control run is already red:\n" + output[-2000:])
            return 1
        print("control: green")
        original = open(ATOM, encoding="utf-8").read()
        caught = 0
        for name, old, new in MUTATIONS:
            if old not in original:
                print("MISSING PATTERN: %s" % name)
                return 1
            path = os.path.join(directory, os.path.relpath(ATOM, ROOT))
            open(path, "w", encoding="utf-8").write(original.replace(old, new, 1))
            code, output = run_check(directory)
            if code == 0:
                print("NOT CAUGHT: %s" % name)
            else:
                caught += 1
                print("caught: %s" % name)
        print("\n%d/%d mutations caught" % (caught, len(MUTATIONS)))
        return 0 if caught == len(MUTATIONS) else 1
    finally:
        shutil.rmtree(directory, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
