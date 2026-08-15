package dev.tushar.forge.githubinstallation;

import dev.tushar.forge.audit.ActorType;
import dev.tushar.forge.audit.AuditLog;
import dev.tushar.forge.githubinstallation.InstallationBindingResult.Bound;
import dev.tushar.forge.githubinstallation.InstallationBindingResult.Reason;
import dev.tushar.forge.githubinstallation.InstallationBindingResult.Rejected;
import dev.tushar.forge.githubinstallation.internal.GithubAppClient;
import dev.tushar.forge.githubinstallation.internal.GithubInstallation;
import dev.tushar.forge.githubinstallation.internal.GithubInstallationRepository;
import dev.tushar.forge.githubinstallation.internal.InstallationSetupNonces;
import dev.tushar.forge.iam.IamQueries;
import dev.tushar.forge.platform.tenancy.TenantScope;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Binds a GitHub App installation to a workspace, and refuses to bind one that is not the
 * caller's.
 *
 * <p><strong>The threat.</strong> GitHub installation ids are small integers that leak into logs,
 * URLs and webhook payloads. The setup callback is a plain GET the browser follows, so anyone who
 * learns an id can invoke it. Without an ownership check, doing so binds a stranger's installation
 * into the attacker's workspace, handing their agent access to repositories they do not own. The
 * whole point of this class is that {@code installation_id} arriving in a query parameter is an
 * assertion, never evidence.
 *
 * <p>Three independent things have to hold, and each covers a gap the others leave:
 *
 * <ol>
 *   <li>a valid single-use nonce issued to <em>this</em> session — stops a forged callback landing
 *       in someone else's browser;
 *   <li>GitHub's own account for the installation matching the caller's GitHub identity — stops id
 *       substitution, which the nonce cannot;
 *   <li>a unique constraint on {@code installation_id} — stops a second workspace claiming it, in
 *       the database rather than in code.
 * </ol>
 */
@Service
public class InstallationBindingService {

    private static final Logger log = LoggerFactory.getLogger(InstallationBindingService.class);

    private static final String RESOURCE_TYPE = "GITHUB_INSTALLATION";

    private final GithubAppClient github;
    private final InstallationSetupNonces nonces;
    private final GithubInstallationRepository installations;
    private final IamQueries iam;
    private final TenantScope tenantScope;
    private final AuditLog audit;

    InstallationBindingService(
            GithubAppClient github,
            InstallationSetupNonces nonces,
            GithubInstallationRepository installations,
            IamQueries iam,
            TenantScope tenantScope,
            AuditLog audit) {
        this.github = github;
        this.nonces = nonces;
        this.installations = installations;
        this.iam = iam;
        this.tenantScope = tenantScope;
        this.audit = audit;
    }

    /** Starts the flow: a nonce bound to this session, to be carried through GitHub and back. */
    public String beginSetup(UUID sessionId) {
        return nonces.issue(sessionId);
    }

    /**
     * Completes the flow.
     *
     * <p>Every rejection is audited. A failed hijack attempt is exactly the event an operator needs
     * to see, and an unrecorded rejection is indistinguishable from one that never happened.
     */
    public InstallationBindingResult completeSetup(
            long installationId, String setupState, UUID sessionId, UUID userId, UUID workspaceId) {

        Optional<UUID> boundSession = nonces.consume(setupState);
        if (boundSession.isEmpty() || !boundSession.get().equals(sessionId)) {
            return reject(workspaceId, userId, installationId, Reason.INVALID_SETUP_STATE);
        }

        // GitHub is asked directly rather than trusting the query parameter. Everything below
        // reasons about this answer.
        Optional<GithubAppClient.InstallationView> fetched = github.fetchInstallation(installationId);
        if (fetched.isEmpty()) {
            return reject(workspaceId, userId, installationId, Reason.UNKNOWN_INSTALLATION);
        }
        GithubAppClient.InstallationView view = fetched.get();

        if (!view.account().isPersonal()) {
            return reject(workspaceId, userId, installationId, Reason.ORGANIZATION_NOT_SUPPORTED);
        }

        // The numeric id, not the login: logins can be renamed and reused, so matching on one
        // matches whoever holds that name today rather than the account we authenticated.
        Optional<String> callerGithubId = iam.githubUserId(userId);
        if (callerGithubId.isEmpty() || !callerGithubId.get().equals(String.valueOf(view.account().id()))) {
            return reject(workspaceId, userId, installationId, Reason.NOT_YOUR_ACCOUNT);
        }

        return persist(workspaceId, userId, view);
    }

    private InstallationBindingResult persist(
            UUID workspaceId, UUID userId, GithubAppClient.InstallationView view) {

        try {
            return tenantScope.runInTenant(workspaceId, () -> {
                // Re-running setup on an installation this workspace already holds is a refresh,
                // not an error: GitHub is the authority on what it currently grants, and the user
                // may have just changed it.
                GithubInstallation installation = installations
                        .findByInstallationId(view.id())
                        .map(existing -> {
                            existing.refreshFrom(view);
                            return existing;
                        })
                        .orElseGet(() -> installations.save(GithubInstallation.bind(workspaceId, userId, view)));

                audit.record(
                        workspaceId,
                        ActorType.HUMAN,
                        userId,
                        "INSTALLATION_BOUND",
                        RESOURCE_TYPE,
                        installation.getId(),
                        Map.of(
                                "installation_id", view.id(),
                                "account_login", view.account().login(),
                                "repository_selection", String.valueOf(view.repositorySelection())));

                return (InstallationBindingResult) new Bound(new InstallationBinding(
                        installation.getId(),
                        installation.getInstallationId(),
                        installation.getAccountLogin(),
                        installation.getAccountType(),
                        String.valueOf(installation.getRepositorySelection())));
            });
        } catch (DataIntegrityViolationException e) {
            // The unique constraint on installation_id fired: another workspace already holds it.
            // Row-level security hid that row from the lookup above, which is why this surfaces as
            // a constraint violation rather than a found row — and why the database, not this
            // method, is what actually guarantees one installation per workspace.
            log.warn("Rejected binding installation {}: already bound to another workspace", view.id());
            return reject(workspaceId, userId, view.id(), Reason.ALREADY_BOUND_ELSEWHERE);
        }
    }

    private InstallationBindingResult reject(UUID workspaceId, UUID userId, long installationId, Reason reason) {
        log.warn("Rejected GitHub installation binding: installation={} reason={}", installationId, reason);

        tenantScope.runInTenant(
                workspaceId,
                () -> audit.record(
                        workspaceId,
                        ActorType.HUMAN,
                        userId,
                        "INSTALLATION_BIND_REJECTED",
                        RESOURCE_TYPE,
                        null,
                        Map.of("installation_id", installationId, "reason", reason.name())));

        return new Rejected(reason);
    }
}
