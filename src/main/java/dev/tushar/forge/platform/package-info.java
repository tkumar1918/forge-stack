/**
 * Platform: cross-cutting infrastructure with no domain knowledge.
 *
 * <p>Every other module may depend on this one. This module must never depend on a domain module.
 * Nested modules ({@code tenancy}, {@code crypto}, ...) expose their own APIs.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Platform")
package dev.tushar.forge.platform;
