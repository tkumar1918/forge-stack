package dev.tushar.forgestack.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one thing about {@link SandboxSpec} that must stay true by construction.
 *
 * <p>§16's strongest claim is that no credential ever enters a sandbox, and the way that claim dies
 * is not a decision — it is a field added in a hurry by somebody making a demo work. A reviewer will
 * not notice {@code String registryToken} in a record with eight components. This will.
 *
 * <p>The Docker suite proves the same thing from the other end, by reading a running container's
 * environment. Both are worth having: this one fails at compile-and-test time on any substrate, and
 * that one catches a credential arriving by a route the spec never modelled.
 */
class SandboxBoundaryTest {

    private static final Set<String> CREDENTIAL_WORDS =
            Set.of("token", "secret", "credential", "password", "apikey", "key", "auth", "bearer", "pat");

    @Test
    @DisplayName("a sandbox spec has nowhere to put a credential")
    void theSpecCannotCarryASecret() {
        assertThat(placesASecretCouldSit(SandboxSpec.class))
                .as("§16: the control plane clones, the sandbox edits, the control plane pushes")
                .isEmpty();
    }

    /** Proof the scan can fail — the same device as {@code AbstractionRulesFireTest}. */
    @Test
    @DisplayName("the check sees a credential when there is one")
    void theCheckFires() {
        assertThat(placesASecretCouldSit(SpecWithASmuggledToken.class)).anyMatch(f -> f.contains("registryToken"));
    }

    @Test
    @DisplayName("the check sees a free-form map, whatever it is called")
    void theCheckFiresOnAMap() {
        assertThat(placesASecretCouldSit(SpecWithSomewhereToHideOne.class)).anyMatch(f -> f.contains("environment"));
    }

    private record SpecWithASmuggledToken(String ociImage, String registryToken) {}

    /** §16's own sketch carried a {@code Map<String,String> env}. That is why it is not copied here. */
    private record SpecWithSomewhereToHideOne(String ociImage, Map<String, String> environment) {}

    private static List<String> placesASecretCouldSit(Class<?> type) {
        List<String> found = new ArrayList<>();
        if (!type.isRecord()) {
            return found;
        }
        for (RecordComponent component : type.getRecordComponents()) {
            String where = type.getSimpleName() + "." + component.getName();
            // String-typed only: in a system billed per token, "token" is also a unit of model input,
            // and a counter is never a credential. The type is what tells them apart.
            if (CharSequence.class.isAssignableFrom(component.getType()) && namedLikeACredential(component.getName())) {
                found.add(where + " (named like a credential)");
            }
            if (Map.class.isAssignableFrom(component.getType())) {
                found.add(where + " (a free-form map is somewhere a secret can hide)");
            }
        }
        return found;
    }

    /** Whole camelCase words: "pat" is inside "path", "key" is inside "monkey". */
    private static boolean namedLikeACredential(String name) {
        for (String word : name.split("(?<!^)(?=[A-Z])")) {
            String normalised = word.toLowerCase(Locale.ROOT);
            if (normalised.endsWith("s") && normalised.length() > 1) {
                normalised = normalised.substring(0, normalised.length() - 1);
            }
            if (CREDENTIAL_WORDS.contains(normalised)) {
                return true;
            }
        }
        return false;
    }
}
