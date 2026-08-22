#!/usr/bin/env bash
# Builds the toolchain image the agent works inside.
#
# Not a Gradle task on purpose: the image is infrastructure a developer builds once, and wiring it
# into `test` would rebuild it on every run and fail the suite on a machine without Docker. The
# tests that need it skip themselves and name this script when it is missing.
set -euo pipefail

cd "$(dirname "$0")/.."
IMAGE="${1:-forgestack/java-21:latest}"

docker build -t "$IMAGE" -f docker/sandbox/Dockerfile docker/sandbox
echo
echo "built $IMAGE"
docker run --rm --user 10001:10001 --network none "$IMAGE" sh -c 'git --version; python3 -V; java -version 2>&1 | head -1'
