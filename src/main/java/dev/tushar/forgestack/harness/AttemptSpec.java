package dev.tushar.forgestack.harness;

import java.util.Set;
import java.util.UUID;

/**
 * Everything a harness is told before it starts, which is less than it would like.
 *
 * <p><strong>Read this record for what is missing.</strong> There is no token, no key, no secret, no
 * environment map a caller could hide one in, and no repository URL the harness could authenticate
 * against. That absence is the §16 rule made structural: repository content is attacker-controlled,
 * it reaches a model holding tools, and a credential in that sandbox is one prompt away from being
 * somebody else's. {@code HarnessBoundaryTest} asserts the absence by reflection, because a field
 * added in a hurry is exactly how this would be lost.
 *
 * <p>The working copy is already on disk when the harness starts. ForgeStack clones it host-side
 * with a token that never leaves the control plane, and collects the diff the same way. Both
 * candidate harnesses make this awkward rather than impossible — their native path is to hand the
 * agent a credential and let it push — so the adapter declines that path and this record gives it
 * nowhere to reappear.
 *
 * @param attemptId    ForgeStack's own id, echoed into harness logs so two systems can be read together
 * @param ociImage     the toolchain image; built by us, never named by a model
 * @param workingCopy  where the code already is, and what it was cloned at
 * @param limits       the ceilings this attempt runs under
 * @param egress       what it may reach, default-deny
 * @param allowedTools the per-attempt, per-phase allowlist (§15)
 */
public record AttemptSpec(
        UUID attemptId,
        String ociImage,
        WorkingCopy workingCopy,
        ResourceLimits limits,
        EgressPolicy egress,
        Set<String> allowedTools) {

    public AttemptSpec {
        if (attemptId == null || workingCopy == null || limits == null || egress == null) {
            throw new IllegalArgumentException("an attempt spec is incomplete");
        }
        if (ociImage == null || ociImage.isBlank()) {
            throw new IllegalArgumentException("an attempt needs an image");
        }
        // Copied so a caller cannot widen the allowlist after the fact by holding onto the set it
        // passed in. §15 requires the allowlist be enforced at dispatch as well as at offer time,
        // and both readings have to agree about what is in it.
        allowedTools = Set.copyOf(allowedTools);
        if (allowedTools.isEmpty()) {
            // An agent with no tools cannot do anything, so this is always a wiring mistake. Failing
            // here costs a second; failing later looks like a model that has stopped trying.
            throw new IllegalArgumentException("an attempt with no tools allowed cannot do anything");
        }
    }

    /**
     * A checkout that is already present, described well enough to verify it.
     *
     * <p>Carries no remote and no credential — see the note on the enclosing record. {@code baseSha}
     * is here so a diff can be taken against a known point rather than against whatever the sandbox
     * happens to think HEAD is, which is the kind of assumption that survives testing and fails on a
     * repository with a dirty submodule.
     */
    public record WorkingCopy(String path, String baseSha) {
        public WorkingCopy {
            if (path == null || path.isBlank() || baseSha == null || baseSha.isBlank()) {
                throw new IllegalArgumentException("a working copy needs a path and a base commit");
            }
        }
    }
}
