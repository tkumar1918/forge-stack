package dev.tushar.forge.audit;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends to the compliance record: who did what, to which resource, under which authority.
 *
 * <p>Write-only by construction. The application database role holds {@code INSERT} and
 * {@code SELECT} on {@code audit_events} and nothing else — {@code UPDATE}, {@code DELETE} and
 * {@code TRUNCATE} are revoked — so an audit trail cannot be rewritten by the code that produces
 * it, however badly that code misbehaves.
 *
 * <p>Plain JDBC rather than JPA: the table is range-partitioned with a composite
 * {@code (id, created_at)} primary key, which an entity mapping models badly for no benefit,
 * because nothing reads these rows through the domain model.
 *
 * <p><strong>Must be called inside {@code TenantScope.runInTenant}.</strong> Row-level security on
 * this table has a {@code WITH CHECK} clause, so an insert without a bound workspace is rejected by
 * Postgres rather than silently landing in the wrong tenant.
 */
@Component
public class AuditLog {

    private static final String INSERT =
            """
            INSERT INTO audit_events
                (workspace_id, actor_type, actor_id, action, resource_type, resource_id, after)
            VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    AuditLog(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Records one auditable action.
     *
     * <p>{@code detail} is for the facts that explain the entry — why a decision went the way it
     * did. Never put a credential in it: these rows outlive the tokens they describe.
     */
    public void record(
            UUID workspaceId,
            ActorType actorType,
            @Nullable UUID actorId,
            String action,
            @Nullable String resourceType,
            @Nullable UUID resourceId,
            Map<String, ?> detail) {

        jdbc.update(
                INSERT,
                workspaceId,
                actorType.name(),
                actorId,
                action,
                resourceType,
                resourceId,
                toJson(detail));
    }

    private @Nullable String toJson(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return json.writeValueAsString(detail);
        } catch (JacksonException e) {
            // An unserialisable detail must not lose the audit row itself — that the action
            // happened matters more than the annotation explaining it. Jackson 3 made these
            // unchecked, so without this catch the failure would propagate and drop the row.
            return "{\"serialization_error\":true}";
        }
    }
}
