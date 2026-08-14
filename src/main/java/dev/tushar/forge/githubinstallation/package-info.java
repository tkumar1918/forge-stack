/**
 * GitHub App: the agent's authority to act on GitHub.
 *
 * <p>Distinct from {@code githubauth}, which only identifies humans. A user must explicitly
 * install the App and choose repositories; that installation, narrowed further by Forge policy
 * and task risk, is the entire basis for anything the agent may do.
 *
 * <p>Nothing in {@code sandbox} may ever depend on this module — enforced by ArchUnit. A GitHub
 * token inside a sandbox that also has egress is a one-prompt exfiltration of a customer's source.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "GitHub App",
        allowedDependencies = {"iam", "platform", "platform::tenancy"})
package dev.tushar.forge.githubapp;
