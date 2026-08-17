package dev.tushar.forgestack.githubinstallation;

import dev.tushar.forgestack.audit.ActorType;
import dev.tushar.forgestack.audit.AuditLog;
import dev.tushar.forgestack.githubinstallation.InstallationBindingResult.Bound;
import dev.tushar.forgestack.githubinstallation.InstallationBindingResult.Reason;
import dev.tushar.forgestack.githubinstallation.InstallationBindingResult.Rejected;
import dev.tushar.forgestack.githubinstallation.internal.app.GithubAppClient;
import dev.tushar.forgestack.githubinstallation.internal.installation.GithubInstallation;
import dev.tushar.forgestack.githubinstallation.internal.installation.GithubInstallationRepository;
import dev.tushar.forgestack.githubinstallation.internal.installation.InstallationSetupNonces;
import dev.tushar.forgestack.iam.IamQueries;
import dev.tushar.forgestack.platform.tenancy.TenantScope;
import java.time.Instant;
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
    private final RepositorySyncService repositorySync;
    private final InstallationTokenService tokens;

    InstallationBindingService(
            GithubAppClient github,
            InstallationSetupNonces nonces,
            GithubInstallationRepository installations,
            IamQueries iam,
            TenantScope tenantScope,
            AuditLog audit,
            RepositorySyncService repositorySync,
            InstallationTokenService tokens) {
        this.github = github;
        this.nonces = nonces;
        this.installations = installations;
        this.iam = iam;
        this.tenantScope = tenantScope;
        this.audit = audit;
        this.repositorySync = repositorySync;
        this.tokens = tokens;
    }

    /** Starts the flow: a nonce bound to this user, to be carried through GitHub and back. */
    public String beginSetup(UUID userId) {
        return nonces.issue(userId);
    }

    /**
     * Completes the flow.
     *
     * <p>Every rejection is audited. A failed hijack attempt is exactly the event an operator needs
     * to see, and an unrecorded rejection is indistinguishable from one that never happened.
     */
    public InstallationBindingResult completeSetup(
            long installationId, String setupState, UUID userId, UUID workspaceId) {

        // A missing nonce and a failed nonce are different events. GitHub's "Redirect on update"
        // sends the user here with an installation_id and no state every time they change
        // repository access from GitHub's own settings — a routine, legitimate action that was
        // being refused as a stale link, with a message blaming the user for switching accounts.
        //
        // So a missing nonce is allowed to *refresh* a binding this workspace already holds, and
        // never to create one. That grants no authority the workspace did not already have, which
        // is what makes it safe to accept without the nonce's protection.
        boolean mayCreateBinding;
        if (setupState == null || setupState.isBlank()) {
            mayCreateBinding = false;
        } else {
            Optional<UUID> boundUser = nonces.consume(setupState);
            if (boundUser.isEmpty()) {
                // A stale link, overwhelmingly: the nonce expired, or the callback was replayed from
                // browser history. Distinct from the case below, which is not routine at all.
                return reject(workspaceId, userId, installationId, Reason.SETUP_STATE_EXPIRED);
            }
            if (!boundUser.get().equals(userId)) {
                return reject(workspaceId, userId, installationId, Reason.SETUP_STATE_FOREIGN);
            }
            mayCreateBinding = true;
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

        InstallationBindingResult result = persist(workspaceId, userId, view, mayCreateBinding);

        if (result instanceof Bound) {
            // Order matters. The sync runs first so the new installation adopts every repository it
            // still exposes; only then is whatever is left pointing at the old installation genuinely
            // unreachable. Retiring first would mark repositories ACCESS_LOST a moment before the
            // sync re-adopted them, and nothing resets that.
            syncRepositories(workspaceId, view.id());
            retireReplacedInstallations(workspaceId, view);
        }
        return result;
    }

    /**
     * Best-effort first sync, after the binding has committed.
     *
     * <p>Deliberately outside the binding transaction. The binding is complete and correct on its
     * own, and GitHub being briefly unavailable must not undo it — especially since the setup nonce
     * is already spent, so the user could not simply retry the callback. A failed sync leaves an
     * empty repository list that the sync endpoint fills in.
     */
    private void syncRepositories(UUID workspaceId, long installationId) {
        try {
            repositorySync.sync(workspaceId, installationId);
        } catch (RuntimeException e) {
            log.warn(
                    "Bound installation {} but could not list its repositories; retry via sync",
                    installationId,
                    e);
        }
    }

    private InstallationBindingResult persist(
            UUID workspaceId, UUID userId, GithubAppClient.InstallationView view, boolean mayCreateBinding) {

        try {
            return tenantScope.runInTenant(workspaceId, () -> {
                // Re-running setup on an installation this workspace already holds is a refresh,
                // not an error: GitHub is the authority on what it currently grants, and the user
                // may have just changed it.
                Optional<GithubInstallation> existing = installations.findByInstallationId(view.id());

                if (existing.isEmpty() && !mayCreateBinding) {
                    // No nonce, and nothing to refresh. Establishing a new binding is the one thing
                    // the nonce still guards, so this is where the flow has to restart properly.
                    return reject(workspaceId, userId, view.id(), Reason.SETUP_NOT_STARTED_HERE);
                }

                GithubInstallation installation = existing
                        .map(found -> {
                            found.refreshFrom(view);
                            return found;
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

    /**
     * Retires any earlier installation this workspace held for the same GitHub account.
     *
     * <p>GitHub allows exactly one installation of an App per account, and reinstalling issues a
     * brand new {@code installation_id} rather than reviving the old one. So a second live row for
     * one account is not an ambiguity to resolve — it is proof the earlier one is dead, and that
     * conclusion is available here without waiting for the uninstall webhook (Phase 6).
     *
     * <p>Without this, an uninstall/reinstall leaves the old installation's repositories in the
     * catalog looking exactly as valid as the new one's, and {@code GET /api/repositories} lists
     * repositories ForgeStack can no longer reach. Confirmed against real GitHub: the superseded
     * installation answers 404 while the new one answers 200.
     *
     * <p>Scoped to one account deliberately. A workspace may legitimately hold installations on
     * several accounts, and those are untouched — only a same-account predecessor can have been
     * replaced.
     *
     * <p>Runs after the sync, in its own transaction, for the ordering reason given at the call
     * site: anything the new installation still exposes has been adopted by then, so what remains
     * on the old row is exactly what is genuinely gone.
     */
    private void retireReplacedInstallations(UUID workspaceId, GithubAppClient.InstallationView view) {
        tenantScope.runInTenant(workspaceId, () -> {
            Instant now = Instant.now();

            for (GithubInstallation stale :
                    installations.findByWorkspaceIdAndAccountIdAndDeletedAtIsNull(workspaceId, view.account().id())) {

                if (stale.getInstallationId() == view.id()) {
                    continue;
                }

                stale.supersededAt(now);
                int unreachable = repositorySync.markInstallationGone(stale.getId());

                // The first caller this has ever had. A token minted against a dead installation is
                // useless, but it is still a credential sitting in Redis with minutes left to run.
                tokens.evict(stale.getInstallationId());

                log.info(
                        "Installation {} replaces {} on account {}; {} repositories are now unreachable",
                        view.id(),
                        stale.getInstallationId(),
                        view.account().login(),
                        unreachable);

                audit.record(
                        workspaceId,
                        ActorType.SYSTEM,
                        null,
                        "INSTALLATION_SUPERSEDED",
                        RESOURCE_TYPE,
                        stale.getId(),
                        Map.of(
                                "superseded_installation_id", stale.getInstallationId(),
                                "replaced_by_installation_id", view.id(),
                                "repositories_now_unreachable", unreachable));
            }
            return null;
        });
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
