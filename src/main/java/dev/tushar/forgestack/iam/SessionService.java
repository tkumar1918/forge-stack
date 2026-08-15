package dev.tushar.forgestack.iam;

import dev.tushar.forgestack.iam.internal.session.Session;
import dev.tushar.forgestack.iam.internal.session.SessionRepository;
import dev.tushar.forgestack.iam.internal.workspace.WorkspaceMember;
import dev.tushar.forgestack.iam.internal.workspace.WorkspaceMemberRepository;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and validates opaque session tokens.
 *
 * <p>The raw token is returned to the caller exactly once, at issue time, and never stored. Only
 * its SHA-256 hash is persisted, so a database leak yields no usable sessions. Lookup hashes the
 * presented token and matches on the hash.
 *
 * <p>Plain SHA-256 rather than a password hash such as bcrypt: the token is 256 bits of
 * {@link SecureRandom} output, so there is no low-entropy secret to protect against brute force,
 * and a deliberately slow hash on every request would be a self-inflicted performance problem.
 * The reason to hash at all is to make stolen database rows useless, and SHA-256 achieves that.
 */
@Service
public class SessionService {

    /** Sessions last a week; the UI refreshes on activity. */
    static final Duration SESSION_TTL = Duration.ofDays(7);

    private static final int TOKEN_BYTES = 32;

    private final SessionRepository sessions;
    private final WorkspaceMemberRepository members;
    private final SecureRandom random = new SecureRandom();

    SessionService(SessionRepository sessions, WorkspaceMemberRepository members) {
        this.sessions = sessions;
        this.members = members;
    }

    @Transactional
    public IssuedSession issue(UUID userId, String userAgent) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        Instant expiresAt = Instant.now().plus(SESSION_TTL);
        Session session = Session.issue(userId, hash(token), userAgent, expiresAt);
        session.selectWorkspace(defaultWorkspaceFor(userId));
        sessions.save(session);

        return new IssuedSession(session.getId(), token, expiresAt);
    }

    /**
     * The workspace a new session starts in.
     *
     * <p>Without this, {@code sessions.workspace_id} stays null and nothing tenant-scoped can be
     * written at all — {@link dev.tushar.forgestack.platform.tenancy.TenantScope} refuses a null
     * workspace, which is the correct fail-closed behaviour but a dead end for the caller.
     *
     * <p>Owned workspaces win over ones merely joined, then workspace id breaks ties, so the choice
     * is stable across logins rather than dependent on row order. Today every user has exactly one
     * — {@code UserProvisioningService} creates a personal workspace at first login — so the
     * ordering only starts to matter once invitations exist.
     */
    private UUID defaultWorkspaceFor(UUID userId) {
        return members.findByIdUserId(userId).stream()
                .min(Comparator.comparingInt((WorkspaceMember member) -> member.getRole() == WorkspaceRole.OWNER ? 0 : 1)
                        .thenComparing(WorkspaceMember::getWorkspaceId))
                .map(WorkspaceMember::getWorkspaceId)
                .orElse(null);
    }

    /**
     * Resolves an active session, refreshing {@code last_seen_at}.
     *
     * <p>Returns empty for unknown, expired, and revoked tokens alike — the caller cannot tell
     * which, and does not need to.
     */
    @Transactional
    public Optional<AuthenticatedSession> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        return sessions.findByTokenHash(hash(token)).filter(session -> session.isActive(now)).map(session -> {
            session.touch(now);
            return new AuthenticatedSession(session.getId(), session.getUserId(), session.getWorkspaceId());
        });
    }

    @Transactional
    public void revoke(String token) {
        sessions.findByTokenHash(hash(token)).ifPresent(session -> session.revoke(Instant.now()));
    }

    /** Revokes every live session for a user — used when membership or credentials change. */
    @Transactional
    public int revokeAllForUser(UUID userId) {
        Instant now = Instant.now();
        var live = sessions.findByUserIdAndRevokedAtIsNull(userId);
        live.forEach(session -> session.revoke(now));
        return live.size();
    }

    private static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; if it is missing the platform is broken.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
