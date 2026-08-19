package dev.tushar.forgestack.harness.openhands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tushar.forgestack.diffguard.DiffGuard;
import dev.tushar.forgestack.diffguard.DiffGuards;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The patch the agent server will not give us.
 *
 * <p>Asserted against {@link DiffGuards} rather than against expected strings, because a diff that
 * merely looks right is worth nothing — the only consumer is §17, and the only question that matters
 * is whether the guards reach the right verdict on it.
 */
class UnifiedDiffsTest {

    private final DiffGuards guards = new DiffGuards();

    @Test
    @DisplayName("a modified file marks only the lines that really changed")
    void onlyRealChangesAreMarked() {
        String diff = UnifiedDiffs.forFile(
                "src/main/java/Orders.java",
                "class Orders {\n  int total() { return 1; }\n}\n",
                "class Orders {\n  int total() { return 2; }\n}\n");

        assertThat(diff.lines().filter(l -> l.startsWith("+") && !l.startsWith("+++")))
                .containsExactly("+  int total() { return 2; }");
        assertThat(diff.lines().filter(l -> l.startsWith("-") && !l.startsWith("---")))
                .containsExactly("-  int total() { return 1; }");
    }

    /**
     * The case that rules out the cheap implementation.
     *
     * <p>Marking every line removed and every line added is a valid unified diff and far less code.
     * It would report this file's pre-existing {@code @Disabled} as newly added, escalating an
     * attempt that never touched it — and a guard that fires on work nobody did gets switched off.
     */
    @Test
    @DisplayName("does not report a pre-existing @Disabled as newly added")
    void untouchedLinesAreNotReportedAsAdded() {
        String before = "class OrdersTest {\n  @Disabled(\"known flaky\")\n  void a() {}\n}\n";
        String after = "class OrdersTest {\n  @Disabled(\"known flaky\")\n  void a() {}\n  void b() {}\n}\n";

        assertThat(guards.check(UnifiedDiffs.forFile("src/test/java/OrdersTest.java", before, after))
                        .passed())
                .isTrue();
    }

    @Test
    @DisplayName("a newly disabled test is still caught")
    void newlyDisabledIsCaught() {
        String before = "class OrdersTest {\n  void a() {}\n}\n";
        String after = "class OrdersTest {\n  @Disabled(\"flaky\")\n  void a() {}\n}\n";

        assertThat(guards.check(UnifiedDiffs.forFile("src/test/java/OrdersTest.java", before, after))
                        .findings())
                .extracting(f -> f.guard())
                .contains(DiffGuard.TEST_DISABLED);
    }

    @Test
    @DisplayName("a deleted file reads as deleted, not as an empty one")
    void deletionUsesDevNull() {
        String diff = UnifiedDiffs.forFile("src/test/java/GoneTest.java", "class GoneTest {}\n", null);

        assertThat(diff).contains("+++ /dev/null");
        assertThat(guards.check(diff).findings())
                .extracting(f -> f.guard())
                .containsExactly(DiffGuard.TEST_DELETED);
    }

    @Test
    @DisplayName("a new file reads as created")
    void creationUsesDevNull() {
        String diff = UnifiedDiffs.forFile("src/test/java/NewTest.java", null, "class NewTest {}\n");

        assertThat(diff).contains("--- /dev/null");
        assertThat(guards.check(diff).passed()).isTrue();
    }

    @Test
    @DisplayName("several files concatenate into one patch the guards read as several")
    void patchesConcatenate() {
        String patch = UnifiedDiffs.patch(List.of(
                UnifiedDiffs.forFile("src/test/java/OneTest.java", "class OneTest {}\n", null),
                UnifiedDiffs.forFile(".github/workflows/ci.yml", "on: [push]\n", "on: []\n")));

        assertThat(guards.check(patch).findings())
                .extracting(f -> f.guard())
                .containsExactlyInAnyOrder(DiffGuard.TEST_DELETED, DiffGuard.CI_CONFIG_CHANGED);
    }

    @Test
    @DisplayName("an enormous file degrades to a wholesale replacement rather than exhausting memory")
    void hugeFilesDegradeGracefully() {
        String big = "line\n".repeat(3_000);
        assertThat(UnifiedDiffs.forFile("generated/Huge.java", big, big + "extra\n"))
                .isNotEmpty();
    }
}
