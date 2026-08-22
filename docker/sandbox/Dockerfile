# The toolchain the agent works inside.
#
# Referenced as forgestack/java-21:latest since Phase 2 and never built until now, so every
# runtime path that named it would have failed on `no such image` the first time it ran.
#
# Two rules govern what may be added here, and both come from §16:
#
#   1. Nothing credential-shaped. No ARG that takes a token, no baked netrc, no registry login.
#      SandboxBoundaryTest asserts the spec has nowhere to put one; this file is the other half.
#   2. Everything the agent needs must already be present. Containers run with --network none
#      until the egress proxy exists, so a toolchain that expects to download anything at run
#      time is a toolchain that does not work.
FROM eclipse-temurin:25-jdk-alpine

# git    — the agent branches and commits locally, and captureDiff reads the result (§16)
# tar    — how a working copy arrives; SandboxProvider.writeFiles pipes one in over exec
# python3— the first fixture language, chosen because its test runner is in the standard
#          library and therefore needs no network. A Java fixture needs a warm Gradle or Maven
#          cache baked into an image, which is the same problem the proxy exists to solve.
#
# The next four are search tools, and they are here for a specific reason: the way an agent
# explores a repository it has never seen is by narrowing — outline, then slice, then read —
# and plain grep/find make the narrowing steps expensive enough that a model skips them and
# reads whole files instead, which is what exhausts a context window.
#
# ripgrep  — respects .gitignore, so a search does not return ten thousand hits from build/
#            and node_modules/. Orders of magnitude faster, and has context lines and a
#            files-only mode built in, both of which save a whole second tool call.
# fd       — the same for filename search.
# ast-grep — structural search: "find every call to this method", not "find this string".
#            Unused by the catalogue today and installed because the refactoring tools that
#            need it are next, and rebuilding the image is the expensive half.
# jq       — so an agent reading a JSON test report does not have to reason about raw JSON.
#
# +36MB on a 491MB image, measured. Cheap against §26's cold-start concern.
RUN apk add --no-cache git tar python3 ripgrep fd ast-grep jq

# uid 10001, matching the --user the provider passes. The container is run non-root regardless,
# so this exists to give that uid a name and a home rather than to grant it anything.
RUN adduser -D -u 10001 forge

# /workspace is deliberately NOT created here, and this is the one line in the file most likely
# to be "tidied" back into a bug.
#
# Measured against a real daemon: a tmpfs whose mountpoint does not exist in the image is created
# drwxrwxrwt, which uid 10001 can write. A tmpfs whose mountpoint DOES exist is created root-owned
# 755 regardless of what the image says that directory's owner is -- chowning it in a layer does
# not help, because the mount replaces the directory rather than inheriting it. So `WORKDIR
# /workspace` here, which creates the path as a side effect, silently makes every sandbox
# read-only to its own agent. That was the first bug this image had.

# Every git operation in the sandbox is local — there is no remote and no credential to reach one.
# These stop git refusing to commit for want of an identity, which is a confusing failure to debug
# from inside a container that cannot ask anybody.
ENV GIT_AUTHOR_NAME=ForgeStack \
    GIT_AUTHOR_EMAIL=agent@forgestack.invalid \
    GIT_COMMITTER_NAME=ForgeStack \
    GIT_COMMITTER_EMAIL=agent@forgestack.invalid

USER 10001:10001
