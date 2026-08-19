/**
 * The agent runtime: what actually picks work up and runs it.
 *
 * <p>In Phase 2 it runs nothing real. The attempt loop, the lease heartbeat, the step log, the retry
 * budget and the escalation path are all here and all exercised; the only thing missing is a model
 * and a sandbox, and a simulated harness stands in for both.
 *
 * <p>Where this module ends is worth being precise about, because {@code runtime} and {@code harness}
 * both sound like "the thing that runs the work". This one owns <em>control flow</em> — which phase,
 * how many attempts, when to escalate, and whether anything was achieved. {@code harness} is the port
 * to the sandbox where a model actually touches the repository. Control flow is the product and stays
 * ours; what is on the other side of that port may not be ours at all (Appendix B).
 *
 * <p>That order is the point of the whole plan (§27). When the agent later does something strange,
 * the queue, the lease, the state machine and the guards will already be known to work — so the
 * question is the prompt, and not four other things at once. Teams that wire the model in first spend
 * months unable to tell those apart.
 *
 * <p>Deliberately depends on neither {@code api} nor {@code iam}: this module is on the extraction
 * path, and the day it becomes its own service the change should be a build file and a transport,
 * not a rewrite.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Runtime",
        allowedDependencies = {"harness", "task", "platform", "platform::jobs", "platform::tenancy"})
package dev.tushar.forgestack.runtime;
