/**
 * The agent's authority to act on GitHub.
 *
 * <p>A user must explicitly install the GitHub App and choose repositories. That installation,
 * narrowed further by ForgeStack policy and task risk, is the entire basis for anything the agent may
 * do — so every credential the agent ever holds is minted here.
 *
 * <p>Nothing in {@code sandbox} may ever depend on this module — enforced by ArchUnit. A GitHub
 * token inside a sandbox that also has egress is a one-prompt exfiltration of a customer's source.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "GitHub Installation",
        allowedDependencies = {"iam", "audit", "platform", "platform::tenancy"})
package dev.tushar.forgestack.githubinstallation;
