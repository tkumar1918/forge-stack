/**
 * Where somebody else's code runs, and what it is allowed to do while it runs.
 *
 * <p>Every other module here decides things. This one is the only place where arbitrary,
 * attacker-influenced code is actually executed, which makes it the security perimeter rather than an
 * infrastructure detail. §16 is the section of the plan that argues about it at length; this package
 * is that argument in code.
 *
 * <p><strong>The rule that shapes everything: no credential ever enters a sandbox.</strong>
 * Repository content is attacker-controlled in the general case — issue bodies, README files,
 * dependency names, code comments — and it reaches a model holding tools. A token in here with any
 * egress at all is a one-prompt exfiltration of the customer's source and, worse, write access to
 * their repositories. So the control plane clones, the sandbox edits, the control plane takes the
 * diff and pushes. {@code SandboxSpec} has nowhere to put a credential and a test asserts it stays
 * that way.
 *
 * <p>Depends on {@code platform} for the {@code @Port} marker and on nothing else. In particular not
 * {@code githubinstallation} — a module that cannot reach the GitHub credentials cannot leak them,
 * which is a stronger guarantee than remembering not to, and §2 makes it a rule the build enforces
 * rather than a convention people recall.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Sandbox",
        allowedDependencies = {"platform"})
package dev.tushar.forgestack.sandbox;
