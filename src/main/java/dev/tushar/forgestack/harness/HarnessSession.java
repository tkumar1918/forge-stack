package dev.tushar.forgestack.harness;

import java.util.UUID;

/**
 * A live agent session, named in a way only its own adapter understands.
 *
 * <p>{@code externalId} is a conversation id, a container id, or whatever the harness on the other
 * side calls it — opaque here on purpose. §16 lists the alternative as a trap worth naming: a handle
 * that exposes a container id invites one caller to shell out to Docker "just this once", and the
 * port stops being a port the moment anyone does.
 *
 * @param attemptId  ForgeStack's id for the work, so the two systems' logs can be joined
 * @param harness    which adapter issued this, so a stale handle fails loudly rather than being
 *                   handed to an adapter that would misread it
 * @param externalId whatever the far side calls this session
 */
public record HarnessSession(UUID attemptId, String harness, String externalId) {

    public HarnessSession {
        if (attemptId == null) {
            throw new IllegalArgumentException("a session belongs to an attempt");
        }
        if (harness == null || harness.isBlank() || externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("a session needs an issuing harness and an external id");
        }
    }
}
