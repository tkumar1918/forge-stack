/**
 * The boundary to the execution plane — where ForgeStack stops being Java.
 *
 * <p>Everything else in this application is a control plane: it decides what should happen, records
 * that it happened, and refuses things. Somewhere a model has to actually read a repository, edit
 * files and run tests, and that somewhere is a process we do not own, written in a language we do
 * not use, inside a sandbox holding a customer's source code. This module is the one place that
 * boundary is described.
 *
 * <p><strong>The harness reports what it did. It never decides whether that was good enough.</strong>
 * Appendix B records this as the single risk that would send us back to building the inner loop
 * ourselves — a harness that insists on owning "the task is done" cannot be adopted, because §10.3
 * requires guards over committed rows to decide that. Rather than leave it to review, the vocabulary
 * here has no way to say it: see {@link dev.tushar.forgestack.harness.StopReason}.
 *
 * <p>No adapter exists yet, deliberately. Appendix B's bake-off is unrun, so the port and its
 * conformance suite are written first and the candidates are measured against them. That order is
 * what makes the choice reversible instead of permanent — and it is the same discipline §16 already
 * applies to {@code SandboxProvider}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Execution harness")
package dev.tushar.forgestack.harness;
