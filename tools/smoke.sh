#!/usr/bin/env bash
# Build the atom, then hand the actual testing to tools/check.py: a real HTTP server stands in for New Relic and
# every expectation (GraphQL document, v2 body, headers, status mapping, read-back id) is recomputed there.
set -euo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null
python3 tools/check.py
