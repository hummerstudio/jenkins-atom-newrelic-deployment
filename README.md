# jenkins-atom-newrelic-deployment

The [newrelic-deployment-notifier](https://plugins.jenkins.io/newrelic-deployment-notifier/) plugin (about 530
installs, 1.13) as an **atom**: one `AtomTask`, no plugin.

The plugin records a deployment marker in New Relic at the end of a build. It has two transports and this atom keeps
both, because which one works depends on the New Relic account:

* `mode=graphql` - the NerdGraph change-tracking mutation, `https://api[.eu].newrelic.com/graphql`, keyed by an
  **entity GUID**;
* `mode=v2` - the legacy REST endpoint, `/v2/applications/{applicationId}/deployments.json`, keyed by an
  **APM application id**.

Both are one synchronous `POST` with `X-Api-Key`; neither has a job model, a poll loop or a credential plugin behind
it, so this is a process-shaped atom.

```
mutation {changeTrackingCreateDeployment(deployment: {version: "1.2.3", entityGuid: "...", commit: "...",
  changelog: "...", description: "...", user: "...", groupId: "...", deploymentType: "BLUE_GREEN", deepLink: "...",
  timestamp: 1700000000}) {deploymentId}}
```

## Inputs

* `apiKey` - New Relic user key (secret; `x-jenkins.secrets.apiKey`).
* `region` - `us` or `eu`: which of the two official hosts is used.
* `baseUrl` - overrides the host (proxy or a test double); empty means the official host.
* `mode` - `graphql` (entityGuid) or `v2` (applicationId).
* `entityGuid` / `applicationId` - which entity the marker belongs to; a GUID for NerdGraph, a number for v2.
* `version` - required; becomes `version` (graphql) or `revision` (v2).
* `commit`, `changelog`, `description`, `user`, `groupId`, `deploymentType`, `deepLink` - optional, sent only when
  non-blank.
* `timestampSeconds` - Unix seconds; empty means "now".
* `timeoutSeconds` - connect and read timeout, default 60.

Outputs: `deploymentId`, `mode`, `region`, `endpoint`, `httpCode`, `version`, `response` (first 400 characters).

## Deliberate differences from the plugin

Each one is asserted by `tools/check.py`.

* The plugin concatenates the GraphQL document and escapes only a double quote, so a newline or a backslash in the
  changelog produces an invalid query. The atom escapes `\`, `"` and control characters on the GraphQL side and
  writes the `{"query": ...}` envelope with a full JSON escaper.
* Field order: the plugin follows the form's widget order, the atom a fixed documented order (`version`,
  `entityGuid`, the optional fields, then `timestamp`).
* A GraphQL answer carrying `errors` is not inspected by the plugin; the build can stay green. The atom turns
  `errors[0].message` into a `40005` failure that quotes it.
* A 2xx answer without a deployment id only logs "Unable to reach New Relic..." in the plugin and still succeeds.
  The atom fails with `40007`: "carried no deployment id, so the marker was not recorded".
* Status mapping: `401/403` is `40006`, `404` is `40003`, `429` is `40004` (with `Retry-After`), anything else is
  `40005` with the body snippet. The plugin folds every failure into one message.
* No retries. The plugin retries three times with a one second sleep; a build step that half-succeeded should fail
  once, visibly.
* The plugin sends `System.currentTimeMillis()` (milliseconds) although the API wants seconds. The atom takes
  `timestampSeconds` in whole seconds; an empty value **omits** the field, so New Relic stamps its own reception
  time - the atom never guesses a clock and never sends milliseconds (`tools/check.py` asserts the field is absent).
* The plugin resolves an application by name by paging `/v2/applications.json`; the atom takes `applicationId`.
  An atom has no New Relic connection to search with, and a name lookup cannot be verified offline.
* Redirects are never followed: the API key must not travel to a second host.

## Build and check

```
mvn verify                  # src/test/java/atom/NewRelicDeploymentIT.java, 20 cases
python3 tools/mutations.py  # 15 deliberate breaks, all must be caught
```

The test starts a real HTTP server, records method/path/headers/raw body, and rebuilds every
expectation from the case's inputs: the GraphQL document is re-derived *and* parsed back field by field, so escaping
and order are both checked; the v2 body is compared as a JSON document; the numeric id is compared as a string; and
`output.json` is read for status/errorCode/data. Failure paths (401/403, 404, 429 with `Retry-After`, 5xx, redirect,
non-JSON, missing id, timeout, unreachable host, every missing input) each have their own case, and one case asserts
the API key never appears in the log or in `output.json`.
