#!/usr/bin/env bash
#
# Runs ForgeStack locally against real GitHub credentials.
#
# Three things have to happen together and each is easy to forget on its own:
# loading .env, relaxing the session cookie, and starting the app.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
    echo "No .env — copy .env.example and fill it in (docs/local-setup.md)." >&2
    exit 1
fi

# Spring Boot does not read .env; nothing else in this project does either. Sourcing
# rather than parsing is deliberate — it lets .env say
#   FORGESTACK_GITHUB_APP_PRIVATE_KEY_PEM="$(cat forgestack-app.pkcs8.pem)"
# so the key stays in its own gitignored file instead of being pasted as one long line.
set -a
# shellcheck disable=SC1091
. ./.env
set +a

# curl withholds a Secure cookie over plain HTTP, so every authenticated request would
# fail with a bare 401 that reads as broken auth. Browsers allow Secure on localhost,
# which makes it worse: the flow works in the browser and fails in curl. Overridden here
# rather than weakened in application.yaml, where it would follow the app to production.
# See docs/known-gaps.md §1.2.
exec ./gradlew bootRun --args='--forgestack.security.cookie-secure=false'
