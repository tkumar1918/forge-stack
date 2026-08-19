package dev.tushar.forgestack.harness.openhands;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a unified diff out of before-and-after file contents.
 *
 * <p>Needed because the agent server has no endpoint that hands back a patch. {@code GET /git/changes}
 * lists which files changed and {@code GET /git/diff} returns one file's {@code original} and
 * {@code modified} contents in full — so the patch our port promises has to be assembled on this
 * side. That is a real cost of the adapter and worth knowing before choosing a harness.
 *
 * <p><strong>Why not just mark every line removed and every line added.</strong> That is a valid
 * unified diff and it is much less code, and it breaks {@code TEST_DISABLED}: a file that already
 * contained an {@code @Disabled} before the attempt would show it as newly added, and every attempt
 * touching that file would be escalated to a person for something it did not do. A guard that cries
 * wolf gets switched off. So the added set has to mean genuinely added, which needs a real diff.
 */
final class UnifiedDiffs {

    /**
     * Beyond this many lines, the diff is emitted as a wholesale replacement.
     *
     * <p>The matrix below is O(n×m) in memory, and a generated or vendored file can be enormous.
     * Degrading is better than an OutOfMemoryError at the end of a twenty-minute attempt — and the
     * cost is bounded: a file this size is not a hand-written test, so the guard most affected by
     * the imprecision is not the one that matters on it.
     */
    private static final int MAX_LINES_FOR_EXACT_DIFF = 4_000;

    private UnifiedDiffs() {}

    /**
     * @param original the file before, or null when it did not exist
     * @param modified the file after, or null when it was deleted
     */
    static String forFile(String path, String original, String modified) {
        if (original == null && modified == null) {
            return "";
        }
        List<String> before = lines(original);
        List<String> after = lines(modified);

        StringBuilder out = new StringBuilder();
        out.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        out.append("--- ").append(original == null ? "/dev/null" : "a/" + path).append('\n');
        out.append("+++ ").append(modified == null ? "/dev/null" : "b/" + path).append('\n');
        out.append("@@ -1,").append(before.size()).append(" +1,").append(after.size()).append(" @@\n");

        for (Edit edit : diff(before, after)) {
            out.append(edit.kind).append(edit.line).append('\n');
        }
        return out.toString();
    }

    /** Concatenates per-file diffs into one patch, in the order given. */
    static String patch(List<String> fileDiffs) {
        StringBuilder out = new StringBuilder();
        fileDiffs.forEach(out::append);
        return out.toString();
    }

    private static List<String> lines(String content) {
        if (content == null || content.isEmpty()) {
            return List.of();
        }
        return List.of(content.split("\n", -1));
    }

    private record Edit(char kind, String line) {}

    /**
     * Longest common subsequence, walked backwards into a list of edits.
     *
     * <p>Not Myers, and deliberately: this runs once per changed file at the end of an attempt, the
     * inputs are source files, and the output only has to be a correct unified diff rather than a
     * minimal one. Reaching for a diff library would add a dependency to save an hour.
     */
    private static List<Edit> diff(List<String> before, List<String> after) {
        List<Edit> edits = new ArrayList<>();
        if (before.size() + after.size() > MAX_LINES_FOR_EXACT_DIFF) {
            before.forEach(line -> edits.add(new Edit('-', line)));
            after.forEach(line -> edits.add(new Edit('+', line)));
            return edits;
        }

        int n = before.size();
        int m = after.size();
        int[][] common = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                common[i][j] = before.get(i).equals(after.get(j))
                        ? common[i + 1][j + 1] + 1
                        : Math.max(common[i + 1][j], common[i][j + 1]);
            }
        }

        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (before.get(i).equals(after.get(j))) {
                edits.add(new Edit(' ', before.get(i)));
                i++;
                j++;
            } else if (common[i + 1][j] >= common[i][j + 1]) {
                edits.add(new Edit('-', before.get(i++)));
            } else {
                edits.add(new Edit('+', after.get(j++)));
            }
        }
        while (i < n) {
            edits.add(new Edit('-', before.get(i++)));
        }
        while (j < m) {
            edits.add(new Edit('+', after.get(j++)));
        }
        return edits;
    }
}
