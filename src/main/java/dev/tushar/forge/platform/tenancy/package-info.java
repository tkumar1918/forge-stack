/**
 * Tenant context and Postgres row-level-security binding.
 *
 * <p>Holds the active workspace for the current thread and binds it to the database session with
 * {@code SET LOCAL app.workspace_id} inside each transaction. {@code SET LOCAL} is mandatory:
 * a plain {@code SET} is connection-scoped and leaks across pooled connections.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Platform :: Tenancy")
package dev.tushar.forge.platform.tenancy;
