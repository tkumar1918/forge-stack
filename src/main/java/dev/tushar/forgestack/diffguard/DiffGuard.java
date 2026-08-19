package dev.tushar.forgestack.diffguard;

/**
 * The ways a diff can make failing work look like passing work.
 *
 * <p>Closed, and ordered roughly by how brazen each one is. A new entry means a new way of gaming
 * verification has been found, which is worth a conversation rather than a quiet commit.
 */
public enum DiffGuard {

    /** A test file was removed outright. The bluntest way to make a suite green. */
    TEST_DELETED,

    /** A test was left in place and switched off — {@code @Disabled}, {@code .skip}, {@code xit}. */
    TEST_DISABLED,

    /**
     * A test kept its name and lost its teeth.
     *
     * <p>The subtle one, and the one worth the most: a test that still runs and still passes but no
     * longer checks anything is invisible to every count of tests and every coverage report.
     */
    ASSERTIONS_REMOVED,

    /**
     * Continuous integration configuration changed.
     *
     * <p>Should be impossible without {@code Workflows:write}, which §7 never requests — so if this
     * ever fires, either the permission model has drifted or something is very wrong. Checked anyway,
     * because defence in depth means not trusting that the other control held.
     */
    CI_CONFIG_CHANGED,

    /** Something credential-shaped was added to the repository. */
    SECRET_INTRODUCED
}
