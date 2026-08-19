package dev.tushar.forgestack.sandbox;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * What to provision, in units no substrate owns.
 *
 * <p>Millicores rather than {@code --cpus}, mebibytes rather than a memory string, and an egress
 * <em>policy</em> rather than a network name. §16 lists the alternative as a real trap: limits
 * written in one substrate's dialect are how a port stops being portable without anyone editing the
 * interface. A Kubernetes adapter reads {@code cpuMillis} into {@code limits.cpu}; a Firecracker one
 * reads it into vCPU; neither has to be taught what {@code --cpus=2} meant.
 *
 * <p><strong>Note what is absent.</strong> No token, no environment map, no registry credential, no
 * repository URL. The absence is the §16 rule made structural rather than remembered, and
 * {@code SandboxBoundaryTest} asserts it by reflection.
 *
 * @param sandboxId   ours, echoed into container labels so an orphan can be traced back to an attempt
 * @param workspaceId the tenant. Reaches the adapter because isolation is per workspace — a shared
 *     network between two customers' sandboxes is a cross-tenant breach, and the adapter is the only
 *     thing positioned to prevent it
 * @param ociImage    a toolchain image we built. Never named by a model
 * @param allowedBinaries what {@code exec} will run. §15's rule that this is an allowlist and not a
 *     shell lives here, at the boundary, rather than in the tool that calls it
 */
public record SandboxSpec(
        UUID sandboxId,
        UUID workspaceId,
        String ociImage,
        int cpuMillis,
        int memoryMib,
        int diskMib,
        Duration ttl,
        EgressPolicy egress,
        Set<String> allowedBinaries) {

    public SandboxSpec {
        if (sandboxId == null || workspaceId == null) {
            throw new IllegalArgumentException("a sandbox belongs to a workspace and has an id");
        }
        if (ociImage == null || ociImage.isBlank()) {
            throw new IllegalArgumentException("a sandbox needs an image");
        }
        if (cpuMillis <= 0 || memoryMib <= 0 || diskMib <= 0) {
            throw new IllegalArgumentException("resource limits must be positive");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("a sandbox without a TTL is a sandbox nobody reaps");
        }
        if (egress == null) {
            throw new IllegalArgumentException("egress must be stated, not defaulted at the far end");
        }
        allowedBinaries = Set.copyOf(allowedBinaries);
        if (allowedBinaries.isEmpty()) {
            throw new IllegalArgumentException("a sandbox that may run nothing cannot verify anything");
        }
    }
}
