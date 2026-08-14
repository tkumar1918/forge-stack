package dev.tushar.forge.platform.tenancy;

/**
 * Thrown when tenant-scoped work is attempted with no active workspace.
 *
 * <p>Row-level security already fails closed in this situation — an unset
 * {@code app.workspace_id} matches no rows — but "the query returned nothing" is a terrible
 * diagnostic. This turns a silent empty result into an obvious error.
 */
public class MissingTenantContextException extends IllegalStateException {

    public MissingTenantContextException() {
        super("No tenant context is active. Tenant-scoped work must run inside TenantScope.");
    }
}
