/**
 * Append-only audit log.
 *
 * <p>Distinct from domain events (module decoupling) and the execution trace (debugging). This is
 * the compliance record: who did what, to which resource, under which authority. The application
 * database role holds INSERT and SELECT only — there is no UPDATE or DELETE grant.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit")
package dev.tushar.forge.audit;
