package dev.tushar.forgestack.platform.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * The workspace the current thread is acting for.
 *
 * <p>Set by {@link TenantScope} and read by anything that needs to know the active tenant. This
 * holder alone grants no database access: isolation is enforced by Postgres row-level security,
 * bound to the transaction by {@code TenantScope}. Setting this without going through
 * {@code TenantScope} therefore changes nothing at the database level, which is the intended
 * failure direction.
 */
public final class TenantContext {

    // A ThreadLocal rather than a ScopedValue: virtual threads are enabled, and each request or
    // job runs on its own carrier-independent thread, so the value cannot leak between tasks.
    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    static void set(UUID workspaceId) {
        CURRENT.set(workspaceId);
    }

    static void clear() {
        CURRENT.remove();
    }

    /** The active workspace, or empty when running outside any tenant. */
    public static Optional<UUID> currentWorkspaceId() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** The active workspace, or {@link MissingTenantContextException} if there is none. */
    public static UUID requireWorkspaceId() {
        UUID workspaceId = CURRENT.get();
        if (workspaceId == null) {
            throw new MissingTenantContextException();
        }
        return workspaceId;
    }
}
