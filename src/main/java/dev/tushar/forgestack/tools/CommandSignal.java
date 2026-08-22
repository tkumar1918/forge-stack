package dev.tushar.forgestack.tools;

/**
 * Something noticed in a command, which may raise its risk.
 *
 * <p><strong>Signals, not verdicts, and never controls.</strong> §17 rates risk to decide what needs
 * a human, not to decide what may run — the container is what contains, and it measures identically
 * whether or not any of these fire. A signal that blocked execution would be a prefix-match filter
 * wearing a better name, and prefix matching on command text is the approach this project and
 * Anthropic have both found does not work.
 *
 * <p>They exist so an operator reading an attempt afterwards can see what the agent reached for, and
 * so §17 can raise a task's rating before asking a person. Missing one costs visibility; it does not
 * open a hole.
 */
public enum CommandSignal {

    /**
     * Output of one command becomes the input of an interpreter — {@code curl … | sh} and family.
     *
     * <p>The canonical way arbitrary remote code gets run, and the one §15 names outright. Worth
     * seeing even inside a sandbox with no network, because the interesting case is the day the
     * egress proxy exists and this stops being impossible.
     */
    PIPE_TO_INTERPRETER,

    /** Reaches for the network: curl, wget, nc. Currently impossible under {@code DENY_ALL}. */
    NETWORK_FETCH,

    /**
     * Writes somewhere that is not the workspace.
     *
     * <p>Contained already — the root filesystem is read-only and everything else is a tmpfs — so
     * this is a signal that the agent believes it is doing something it is not, which is worth
     * knowing for a different reason than danger.
     */
    REDIRECT_OUTSIDE_WORKSPACE,

    /**
     * Installs a dependency.
     *
     * <p>§17 wants this visible because it changes what the verification contract is verifying:
     * tests that pass only after an unrecorded install are tests that will not pass in CI.
     */
    PACKAGE_INSTALL,

    /**
     * Something shaped like a credential appears in the command text.
     *
     * <p>Almost always the agent having invented one, since §16 puts none in the sandbox. That is
     * exactly why it is worth surfacing: a model writing a plausible-looking token into a command is
     * a model that has misunderstood where it is, and the same confusion tends to produce a commit.
     */
    CREDENTIAL_LITERAL,

    /** Attempts to gain privilege: sudo, su, setuid bits. Refused by the kernel; recorded anyway. */
    PRIVILEGE_ESCALATION,

    /**
     * Deletes broadly — {@code rm -rf} against a wide path.
     *
     * <p>Contained, and recoverable, because the sandbox is thrown away and the diff is what leaves.
     * It is a signal about the agent's judgement rather than about the filesystem.
     */
    BROAD_DELETE
}
