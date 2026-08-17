package dev.tushar.forgestack.task;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tushar.forgestack.platform.tenancy.TenantScope;
import dev.tushar.forgestack.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** One worker per task, and what it takes to still be that worker a minute later. */
class TaskLeaseTest extends AbstractIntegrationTest {

    private static final Duration TTL = Duration.ofSeconds(60);

    @Autowired
    private TaskLeases leases;

    @Autowired
    private TenantScope tenantScope;

    @Autowired
    private JdbcTemplate jdbc;

    private TaskRows rows;
    private UUID workspaceId;
    private UUID taskId;

    @BeforeEach
    void aTaskToClaim() {
        this.rows = new TaskRows(tenantScope, jdbc);
        this.workspaceId = rows.newWorkspace();
        this.taskId = rows.newTask(workspaceId, "QUEUED");
    }

    @Test
    @DisplayName("an unclaimed task can be claimed")
    void aFreeTaskCanBeClaimed() {
        Optional<Lease> lease = leases.acquire(workspaceId, taskId, "worker-1", TTL);

        assertThat(lease).isPresent();
        assertThat(lease.get().owner()).isEqualTo("worker-1");
        assertThat(lease.get().epoch()).isPositive();
        assertThat(lease.get().expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    @DisplayName("a claimed task cannot be claimed by anyone else")
    void aClaimedTaskIsNotAvailable() {
        leases.acquire(workspaceId, taskId, "worker-1", TTL);

        assertThat(leases.acquire(workspaceId, taskId, "worker-2", TTL))
                .as("an empty result is the ordinary answer to a duplicate delivery, not an error")
                .isEmpty();
    }

    @Test
    @DisplayName("giving a claim back makes the task available at once")
    void releasingFreesTheTask() {
        Lease lease = leases.acquire(workspaceId, taskId, "worker-1", TTL).orElseThrow();

        assertThat(leases.release(workspaceId, lease)).isTrue();

        // The point of releasing rather than letting the lease lapse: a graceful handover costs no
        // waiting, and a whole TTL of dead time per deploy is what makes people stop draining.
        assertThat(leases.acquire(workspaceId, taskId, "worker-2", TTL)).isPresent();
    }

    @Test
    @DisplayName("a worker keeps its claim by renewing it")
    void renewingExtendsTheClaim() {
        Lease lease = leases.acquire(workspaceId, taskId, "worker-1", Duration.ofSeconds(5))
                .orElseThrow();

        assertThat(leases.renew(workspaceId, lease, Duration.ofSeconds(120))).isTrue();

        assertThat(leases.current(workspaceId, taskId).orElseThrow().expiresAt())
                .isAfter(lease.expiresAt());
    }

    @Test
    @DisplayName("a claim on one workspace's task is invisible to another")
    void leasesAreTenantScoped() {
        UUID stranger = rows.newWorkspace();

        assertThat(leases.acquire(stranger, taskId, "worker-1", TTL))
                .as("row-level security, not a WHERE clause, is what makes this empty")
                .isEmpty();
    }
}
