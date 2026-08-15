/**
 * Tenant context and Postgres row-level-security binding.
 *
 * <p>Holds the active workspace for the current thread and binds it to the database session with
 * {@code SET LOCAL app.workspace_id} inside each transaction. {@code SET LOCAL} is mandatory:
 * a plain {@code SET} is connection-scoped and leaks across pooled connections.
 *
 * <p>A <em>named interface</em> of {@code platform} rather than a nested module. Modulith forbids a
 * sibling module from reaching into another module's nested module, so as a nested module this
 * package was unreachable — every tenant-scoped write in the application would have had to live in
 * {@code platform} itself. A named interface is the construct that says "part of platform, and
 * deliberately public": callers declare {@code platform::tenancy} to get it.
 */
@org.springframework.modulith.NamedInterface("tenancy")
package dev.tushar.forgestack.platform.tenancy;
