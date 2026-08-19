package dev.tushar.forgestack.diffguard;

import java.util.List;

/**
 * What the guards made of a diff.
 *
 * <p>A refusal here is a <em>policy</em> failure and not a test failure, which §17 is emphatic about:
 * the tests may well be green, and that is exactly the problem. It forces a person to look rather
 * than costing the task a retry, because retrying will not stop an agent doing the same reasonable-
 * looking thing again.
 */
public record DiffVerdict(List<DiffFinding> findings) {

    public DiffVerdict {
        findings = List.copyOf(findings);
    }

    public static DiffVerdict clean() {
        return new DiffVerdict(List.of());
    }

    public boolean passed() {
        return findings.isEmpty();
    }

    /** One line per finding, for the attempt row and for whoever reads it next. */
    public String summary() {
        return passed() ? "no diff-guard findings" : findings.stream().map(DiffFinding::toString).reduce("", (a, b) -> a.isEmpty() ? b : a + "; " + b);
    }
}
