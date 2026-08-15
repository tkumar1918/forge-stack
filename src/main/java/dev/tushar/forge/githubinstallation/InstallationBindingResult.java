package dev.tushar.forge.githubinstallation;

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
        /** No such nonce, already used, expired, or issued to a different session. */
        INVALID_SETUP_STATE,

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
         * Installed on an organization, which Forge cannot yet verify.
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
