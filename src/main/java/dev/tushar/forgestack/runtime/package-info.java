/**
 * The agent runtime: what actually picks work up and runs it.
 *
 * <p>In Phase 2 it runs nothing real. The attempt loop, the lease heartbeat, the step log, the retry
 * budget and the escalation path are all here and all exercised; the only thing missing is a model
 * and a sandbox, and a fake handler stands in for both.
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
        allowedDependencies = {"task", "platform", "platform::jobs", "platform::tenancy"})
package dev.tushar.forgestack.runtime;
