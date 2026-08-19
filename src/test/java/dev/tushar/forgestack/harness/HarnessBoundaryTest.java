package dev.tushar.forgestack.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two things about this port that must stay true by construction.
 *
 * <p>Both are recorded in Appendix B as risks that would send us back to building the inner loop
 * ourselves, and both are the kind of thing that erodes one reasonable-looking commit at a time. A
 * reviewer will not notice a field called {@code githubToken} added to a spec record eight months
 * from now, and will certainly not notice a {@code COMPLETED} value appended to an enum. These tests
 * will.
 */
class HarnessBoundaryTest {

    /**
     * Words that mean somebody is about to hand a sandbox a credential.
     *
     * <p>Matched as whole camelCase words against {@code String}-typed components only, which is what
     * makes the list usable rather than merely alarming. The first draft matched substrings and
     * immediately flagged {@code WorkingCopy.path} — for containing "pat" — and
     * {@code ResourceLimits.maxModelTokens}, which is a cost meter.
     *
     * <p>That second one is worth keeping in mind rather than just fixing. In a system built around
     * language models, <em>token</em> means two unrelated things: a credential, and a unit of model
     * input we are billed for. The type is what tells them apart — a credential is a {@code String}
     * and a count never is — so the type is what this checks.
     */
    private static final Set<String> CREDENTIAL_WORDS =
            Set.of("token", "secret", "credential", "password", "apikey", "key", "auth", "bearer", "pat");

    /** Words that would mean the harness had been given a vote on whether the work was acceptable. */
    private static final Set<String> VERDICT_WORDS =
            Set.of("complete", "completed", "success", "succeeded", "passed", "verified", "done", "ok", "merged");

    /**
     * §16's rule, enforced against the shape of the data rather than against anyone's memory of it.
     *
     * <p>Repository content is attacker-controlled, it reaches a model holding tools, and a
     * credential in that sandbox is one prompt away from being somebody else's — along with write
     * access to the customer's repositories. Both candidate harnesses would rather be handed a token;
     * their native flow is for the agent to push its own branch. The adapter declines, and this test
     * makes sure the spec never grows somewhere for the token to reappear.
     */
    @Test
    @DisplayName("an attempt spec has nowhere to put a credential")
    void theSpecCannotCarryASecret() {
        List<String> offending = credentialShapedComponents(AttemptSpec.class, new LinkedHashSet<>());

        assertThat(offending)
                .as("§16: no credential ever enters the sandbox — host-broker it instead")
                .isEmpty();
    }

    /**
     * Appendix B's boundary: the harness reports outcomes, ForgeStack decides transitions.
     *
     * <p>§10.3 gives completion eight mechanical guards over committed rows, of which "the model
     * thinks it is finished" is not one. If this enum ever gains a value meaning success, every one
     * of those guards becomes bypassable by a harness reporting it — so the vocabulary is kept
     * incapable of saying it, and adding one has to start by deleting this test.
     */
    @Test
    @DisplayName("a harness has no way to report that the work was any good")
    void stopReasonCannotExpressSuccess() {
        assertThat(verdictNamesIn(StopReason.class))
                .as("guards over committed rows decide completion (§10.3), never the harness")
                .isEmpty();
    }

    /**
     * The port itself must not grow a verdict method either.
     *
     * <p>The enum is the obvious place to smuggle one in; a {@code boolean succeeded(session)} on the
     * interface is the second most obvious, and would be just as fatal.
     */
    @Test
    @DisplayName("the port exposes no method that asks whether it worked")
    void thePortCannotBeAskedForAVerdict() {
        assertThat(verdictMethodsOn(ExecutionHarness.class)).isEmpty();
    }

    // --- proof the three checks above can actually fail ----------------------------------------
    //
    // Every one of them passes today by asserting a list is empty, which is also exactly what they
    // would do if they were scanning nothing at all. Two rules in this codebase spent months green
    // for that reason. So each detector is pointed at something deliberately wrong, and has to see
    // it. If a refactor guts a scan, these fail on the same commit rather than a year later.

    @Test
    @DisplayName("the credential check sees a token when there is one")
    void theCredentialCheckFires() {
        assertThat(credentialShapedComponents(SpecWithASmuggledToken.class, new LinkedHashSet<>()))
                .anyMatch(finding -> finding.contains("githubToken"));
    }

    @Test
    @DisplayName("the credential check sees a free-form map, whatever it is called")
    void theCredentialCheckFiresOnAMap() {
        assertThat(credentialShapedComponents(SpecWithSomewhereToHideOne.class, new LinkedHashSet<>()))
                .anyMatch(finding -> finding.contains("environment"));
    }

    @Test
    @DisplayName("the credential check reaches inside nested records")
    void theCredentialCheckRecurses() {
        assertThat(credentialShapedComponents(SpecWithANestedSecret.class, new LinkedHashSet<>()))
                .anyMatch(finding -> finding.contains("apiKey"));
    }

    @Test
    @DisplayName("the verdict check sees a success value when there is one")
    void theVerdictCheckFires() {
        assertThat(verdictNamesIn(StopReasonThatOversteps.class)).contains("COMPLETED");
    }

    @Test
    @DisplayName("the verdict check sees a verdict method when there is one")
    void theVerdictMethodCheckFires() {
        assertThat(verdictMethodsOn(HarnessThatOversteps.class)).contains("succeeded");
    }

    /** A spec that hands the sandbox a GitHub token — the §16 mistake, in one field. */
    private record SpecWithASmuggledToken(String ociImage, String githubToken) {}

    /** No incriminating name, and just as fatal: anything at all can be put in a map. */
    private record SpecWithSomewhereToHideOne(String ociImage, java.util.Map<String, String> environment) {}

    private record SpecWithANestedSecret(String ociImage, ModelAccess model) {}

    private record ModelAccess(String baseUrl, String apiKey) {}

    private enum StopReasonThatOversteps {
        PAUSED,
        COMPLETED
    }

    private interface HarnessThatOversteps {
        boolean succeeded(String session);
    }

    // -------------------------------------------------------------------------------------------

    private static List<String> verdictNamesIn(Class<? extends Enum<?>> type) {
        List<String> verdicts = new ArrayList<>();
        for (Enum<?> value : type.getEnumConstants()) {
            if (!verdictWordsIn(value.name()).isEmpty()) {
                verdicts.add(value.name());
            }
        }
        return verdicts;
    }

    private static List<String> verdictMethodsOn(Class<?> type) {
        List<String> verdicts = new ArrayList<>();
        for (var method : type.getDeclaredMethods()) {
            if (!verdictWordsIn(method.getName()).isEmpty()) {
                verdicts.add(method.getName());
            }
        }
        return verdicts;
    }

    /** Substring matching here, unlike the credential scan: there is no innocent use of "succeeded". */
    private static List<String> verdictWordsIn(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return VERDICT_WORDS.stream().filter(lower::contains).toList();
    }

    /** Walks a record's components, and the records inside them, collecting places a secret could sit. */
    private static List<String> credentialShapedComponents(Class<?> type, Set<Class<?>> seen) {
        List<String> offending = new ArrayList<>();
        if (!type.isRecord() || !seen.add(type)) {
            return offending;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            String where = type.getSimpleName() + "." + component.getName();

            if (CharSequence.class.isAssignableFrom(component.getType())
                    && !credentialWordsIn(component.getName()).isEmpty()) {
                offending.add(where + " (named like a credential)");
            }
            // The other way one arrives, and the reason §16's own SandboxSpec sketch is not copied
            // verbatim: that record carried a Map<String,String> env, which is a place to put a
            // token that no name-based check would ever notice. This port deliberately has no such
            // field, and this is what keeps it that way.
            if (java.util.Map.class.isAssignableFrom(component.getType())) {
                offending.add(where + " (a free-form map is somewhere a secret can hide)");
            }
            offending.addAll(credentialShapedComponents(component.getType(), seen));
        }
        return offending;
    }

    /**
     * The credential words in a camelCase name, matched whole.
     *
     * <p>Whole words because substrings are useless here: "pat" is in "path", "key" is in "monkey".
     * A trailing "s" is trimmed so {@code accessTokens} is caught alongside {@code accessToken}.
     */
    private static List<String> credentialWordsIn(String componentName) {
        List<String> found = new ArrayList<>();
        for (String word : componentName.split("(?<!^)(?=[A-Z])")) {
            String normalised = word.toLowerCase(Locale.ROOT);
            if (normalised.endsWith("s") && normalised.length() > 1) {
                normalised = normalised.substring(0, normalised.length() - 1);
            }
            if (CREDENTIAL_WORDS.contains(normalised)) {
                found.add(word);
            }
        }
        return found;
    }
}
