package dev.tushar.forgestack.sandbox;

import java.util.UUID;

/**
 * A live sandbox, named in a way only its own adapter understands.
 *
 * <p>{@code externalId} is a container id, a pod name, or whatever the substrate calls it — opaque
 * here on purpose. §16 names the alternative as a trap worth avoiding by construction: a handle that
 * exposed a container id would invite one caller to shell out to Docker "just this once", and the
 * port would stop being a port the moment anyone did.
 */
public record SandboxHandle(UUID sandboxId, UUID workspaceId, String provider, String externalId) {

    public SandboxHandle {
        if (sandboxId == null || workspaceId == null) {
            throw new IllegalArgumentException("a handle names a sandbox and its workspace");
        }
        if (provider == null || provider.isBlank() || externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("a handle needs an issuing provider and an external id");
        }
    }
}
