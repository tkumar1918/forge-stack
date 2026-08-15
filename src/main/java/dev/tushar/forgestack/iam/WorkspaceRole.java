package dev.tushar.forgestack.iam;

/**
 * A member's role within a workspace.
 *
 * <p>Four roles, deliberately. Richer RBAC is deferred until a customer asks for it.
 *
 * <p>Part of the module's public API rather than nested inside the entity, so authorization
 * decisions elsewhere can be expressed without importing persistence types.
 */
public enum WorkspaceRole {
    OWNER,
    ADMIN,
    MAINTAINER,
    VIEWER
}
