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
# DevTools restart is off, and that is a deliberate trade.
#
# It watches build/classes, so *any* `./gradlew test` in another terminal recompiles main classes and
# triggers a restart — which re-runs Flyway against whatever migration files happen to be on disk at
# that instant. Editing a migration to watch a test fail, the normal way to prove a constraint works,
# therefore applies the half-written version to the dev database. The next restart then fails
# checksum validation and the app will not start at all, with an error about entityManagerFactory
# that names nothing to do with migrations.
#
# That cost real time four separate times before it was worth a line of script. Restart the app by
# hand to pick up changes; drop this flag if you want live reload and are not touching db/migration.
exec ./gradlew bootRun --args='--forgestack.security.cookie-secure=false --spring.devtools.restart.enabled=false'
