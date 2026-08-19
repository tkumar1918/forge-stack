package dev.tushar.forgestack.diffguard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link DiffGuard} over a patch.
 *
 * <p>Every check is a pure function of the diff text. Nothing here reads the model's explanation of
 * what it did, because a persuasive explanation for deleting a test is exactly what both a helpful
 * model and a compromised one would produce.
 *
 * <p><strong>These are heuristics, and they are tuned to fire.</strong> A false positive costs a
 * person thirty seconds; a false negative ships a green build with the failing test deleted out of
 * it. Where the two trade off, they trade off towards noise — and because a finding escalates rather
 * than failing the task, the cost of being wrong stays on the cheap side.
 */
@Component
public class DiffGuards {

    /** Paths that are a test by convention, across the languages a repository is likely to be in. */
    private static final Pattern TEST_PATH = Pattern.compile(
            ".*(^|/)(test|tests|spec|__tests__)/.*"
                    + "|.*(Test|Tests|Spec|IT|ITCase)\\.(java|kt|scala|groovy)$"
                    + "|.*(^|/)test_[^/]+\\.py$|.*_test\\.(py|go|rb)$"
                    + "|.*\\.(test|spec)\\.(js|jsx|ts|tsx)$",
            Pattern.CASE_INSENSITIVE);

    /** Turning a test off without removing it. */
    private static final Pattern DISABLING = Pattern.compile(
            "@Disabled\\b|@Ignore\\b|@Test\\s*\\(\\s*enabled\\s*=\\s*false"
                    + "|\\b(it|describe|test)\\.skip\\s*\\(|\\bxit\\s*\\(|\\bxdescribe\\s*\\("
                    + "|@pytest\\.mark\\.skip|\\bunittest\\.skip\\b|\\bt\\.Skip\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /** A line that checks something. Crude, and it only ever gets compared against itself. */
    private static final Pattern ASSERTION = Pattern.compile(
            "\\bassert\\w*\\s*[(.]|\\bexpect\\s*\\(|\\bshould\\b|\\bverify\\s*\\(" + "|\\brequire\\.\\w+\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CI_CONFIG = Pattern.compile(
            "^\\.github/workflows/.*|^\\.gitlab-ci\\.yml$|^Jenkinsfile$|^\\.circleci/.*"
                    + "|^\\.travis\\.yml$|^azure-pipelines\\.yml$|^\\.buildkite/.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Credential shapes, not credential names.
     *
     * <p>Matching on the value's structure rather than on a nearby word, because the word is the part
     * an author controls. A private-key header is a private-key header whatever the variable is
     * called.
     */
    private static final List<Pattern> SECRET_SHAPES = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
            Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"),
            Pattern.compile("\\b(sk|rk)_(live|test)_[A-Za-z0-9]{16,}\\b"),
            Pattern.compile("(?i)\\b(password|passwd|secret|api[_-]?key|token)\\s*[:=]\\s*[\"'][^\"'\\s]{8,}[\"']"));

    public DiffVerdict check(String unifiedDiff) {
        List<DiffFinding> findings = new ArrayList<>();
        for (UnifiedDiff.FileChange file : UnifiedDiff.parse(unifiedDiff).files()) {
            boolean isTest = TEST_PATH.matcher(file.path()).matches();

            if (isTest && file.deleted()) {
                findings.add(new DiffFinding(DiffGuard.TEST_DELETED, file.path(), "the test file was removed"));
                // Nothing else is worth saying about a file that is gone; its every line counts as
                // removed, and reporting "assertions removed" too would just be noise on top.
                continue;
            }
            if (isTest) {
                findings.addAll(disabledTests(file));
                weakenedAssertions(file).ifPresent(findings::add);
            }
            if (CI_CONFIG.matcher(file.path()).matches()) {
                findings.add(new DiffFinding(
                        DiffGuard.CI_CONFIG_CHANGED,
                        file.path(),
                        file.deleted() ? "CI configuration was deleted" : "CI configuration was modified"));
            }
            findings.addAll(introducedSecrets(file));
        }
        return new DiffVerdict(findings);
    }

    private static List<DiffFinding> disabledTests(UnifiedDiff.FileChange file) {
        List<DiffFinding> findings = new ArrayList<>();
        for (String line : file.added()) {
            if (DISABLING.matcher(line).find()) {
                findings.add(new DiffFinding(DiffGuard.TEST_DISABLED, file.path(), "added: " + line.trim()));
            }
        }
        return findings;
    }

    /**
     * More checks left than arrived.
     *
     * <p>Counted rather than matched line for line, because a refactor legitimately rewrites
     * assertions and a net count survives that while still catching a wholesale removal. A file that
     * loses three assertions and gains three is silent; one that loses three and gains none is not.
     */
    private static java.util.Optional<DiffFinding> weakenedAssertions(UnifiedDiff.FileChange file) {
        long removed = file.removed().stream()
                .filter(line -> ASSERTION.matcher(line).find())
                .count();
        long added = file.added().stream()
                .filter(line -> ASSERTION.matcher(line).find())
                .count();
        if (removed > added) {
            return java.util.Optional.of(new DiffFinding(
                    DiffGuard.ASSERTIONS_REMOVED,
                    file.path(),
                    "%d assertion line(s) removed, %d added".formatted(removed, added)));
        }
        return java.util.Optional.empty();
    }

    private static List<DiffFinding> introducedSecrets(UnifiedDiff.FileChange file) {
        List<DiffFinding> findings = new ArrayList<>();
        for (String line : file.added()) {
            for (Pattern shape : SECRET_SHAPES) {
                if (shape.matcher(line).find()) {
                    // The matched value is never quoted back. This string reaches logs, a transition
                    // row and a person's screen, and a guard that leaked the credential it caught
                    // would be worse than one that missed it.
                    findings.add(new DiffFinding(
                            DiffGuard.SECRET_INTRODUCED,
                            file.path(),
                            "a credential-shaped value was added (%s)".formatted(describe(shape))));
                    break;
                }
            }
        }
        return findings;
    }

    private static String describe(Pattern shape) {
        String source = shape.pattern().toLowerCase(Locale.ROOT);
        if (source.contains("private key")) {
            return "private key block";
        }
        if (source.contains("akia")) {
            return "AWS access key id";
        }
        if (source.contains("gh[pousr]")) {
            return "GitHub token";
        }
        if (source.contains("xox")) {
            return "Slack token";
        }
        if (source.contains("live|test")) {
            return "payment provider key";
        }
        return "assignment to a secret-shaped name";
    }
}
