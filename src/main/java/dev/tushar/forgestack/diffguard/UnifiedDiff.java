package dev.tushar.forgestack.diffguard;

import java.util.ArrayList;
import java.util.List;

/**
 * A unified diff, broken into what changed per file.
 *
 * <p>Deliberately small. A full patch parser handles renames, mode changes, binary hunks and
 * combined diffs, and none of that changes any answer here: every guard asks either "which file" or
 * "what lines appeared and disappeared". Parsing more than that would be surface with no question
 * behind it.
 *
 * <p>Tolerant on purpose — a malformed hunk yields fewer findings, never an exception. This runs at
 * the end of an attempt that may have taken twenty minutes, and throwing away that work because a
 * patch had an odd header would be a worse failure than missing one check. Whatever it does parse is
 * still checked.
 */
record UnifiedDiff(List<FileChange> files) {

    static UnifiedDiff parse(String diff) {
        List<FileChange> files = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return new UnifiedDiff(files);
        }

        String path = null;
        boolean deleted = false;
        boolean created = false;
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (String line : diff.split("\\R")) {
            if (line.startsWith("diff --git ")) {
                if (path != null) {
                    files.add(new FileChange(path, deleted, created, List.copyOf(added), List.copyOf(removed)));
                }
                path = pathFromHeader(line);
                deleted = false;
                created = false;
                added = new ArrayList<>();
                removed = new ArrayList<>();
            } else if (line.startsWith("--- ")) {
                created = line.endsWith("/dev/null");
            } else if (line.startsWith("+++ ")) {
                deleted = line.endsWith("/dev/null");
                // Prefer the post-image path: it is the name the file has after the change, which is
                // what a rename should be judged by.
                String after = stripPrefix(line.substring(4).trim());
                if (!after.equals("/dev/null")) {
                    path = after;
                }
            } else if (line.startsWith("+")) {
                added.add(line.substring(1));
            } else if (line.startsWith("-")) {
                removed.add(line.substring(1));
            }
        }
        if (path != null) {
            files.add(new FileChange(path, deleted, created, List.copyOf(added), List.copyOf(removed)));
        }
        return new UnifiedDiff(files);
    }

    /** {@code diff --git a/x b/x} — the b-side, which survives a rename. */
    private static String pathFromHeader(String line) {
        String[] parts = line.substring("diff --git ".length()).trim().split("\\s+");
        return stripPrefix(parts[parts.length - 1]);
    }

    private static String stripPrefix(String path) {
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }

    record FileChange(String path, boolean deleted, boolean created, List<String> added, List<String> removed) {}
}
