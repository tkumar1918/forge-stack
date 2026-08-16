package dev.tushar.forgestack.githubinstallation;

/**
 * The outcome of trying to bind a GitHub App installation to a workspace.
 *
 * <p>A sealed result rather than exceptions, because rejection is an expected outcome of this flow
 * rather than a fault: every {@link Reason} below is something a legitimate user can hit, and the
 * caller has to render each one differently. Making the set closed means a new rejection cannot be
 * added without every caller being forced to decide what to do with it.
 */
public sealed interface InstallationBindingResult {

    record Bound(InstallationBinding installation) implements InstallationBindingResult {}

    record Rejected(Reason reason) implements InstallationBindingResult {}

    enum Reason {
        /**
         * No such nonce: expired, already spent, or never issued.
         *
         * <p>Split from {@link #SETUP_STATE_FOREIGN} because collapsing the two made the first real
         * failure undiagnosable — one WARN line and one audit row covered a stale link and a
         * possible CSRF attempt equally well. Same lesson as the 401/404 collapse in
         * {@code GithubAppClient}: an operator has to be able to tell which happened.
         *
         * <p>Safe to explain to the caller. It is a fact about their own link, not about anyone
         * else's installation, so it is no oracle.
         */
        SETUP_STATE_EXPIRED,

        /**
         * A live nonce, issued to a different user.
         *
         * <p>The CSRF rejection. Rendered to the caller identically to
         * {@link #SETUP_STATE_EXPIRED}; distinct in the log and the audit row, where it is the one
         * worth alerting on.
         */
        SETUP_STATE_FOREIGN,

        /** GitHub does not know this installation id. */
        UNKNOWN_INSTALLATION,

        /**
         * The installation belongs to a GitHub account that is not the caller's.
         *
         * <p>This is the anti-hijack rejection. Installation ids are guessable and leak into logs;
         * without this check anyone holding one could bind a stranger's repositories into their own
         * workspace.
         */
        NOT_YOUR_ACCOUNT,

        /**
         * Installed on an organization, which ForgeStack cannot yet verify.
         *
         * <p>Confirming the caller administers an organization needs the {@code Members}
         * permission, which the App deliberately does not request — an App that can read every
         * customer's org membership is a worse trade than not supporting org installs yet. Fails
         * closed until that is designed properly.
         */
        ORGANIZATION_NOT_SUPPORTED,

        /** Already bound to a different workspace; the database refused a second binding. */
        ALREADY_BOUND_ELSEWHERE
    }
}
