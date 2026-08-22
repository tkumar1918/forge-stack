package dev.tushar.forgestack.tools;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every tool that exists, written down in one table.
 *
 * <p>Closed on purpose, and for the same reason the state machine's transition table is closed:
 * adding a capability should be an edit somebody reviews, in a place where the whole set can be read
 * at once and the new row compared against its neighbours. A registry that tools add themselves to
 * gives a reviewer nothing to look at.
 *
 * <p><strong>What is deliberately not here yet, and why</strong> — because a catalogue's omissions
 * are as much a decision as its contents (§27's "subtraction becomes invisible when it is never
 * written down as a choice"):
 *
 * <ul>
 *   <li>{@code run_tests} — needs the human-declared {@code VerificationContract} to say what the
 *       test command is. Offering it before that exists would mean the <em>model</em> choosing what
 *       "verified" means for this repository, which is the one thing §10.3 and §17 are built to
 *       prevent. It waits for Phase 5 rather than shipping as a synonym for {@code run_command}.
 *   <li>{@code start_process}, {@code signal_process}, {@code read_process_output} — the sandbox port
 *       cannot express a process outliving a call (known-gaps §3.19).
 *   <li>{@code lsp_*} — needs background processes first.
 *   <li>{@code github_open_pr}, {@code github_comment} — host-brokered, and no tool in this module
 *       may hold a credential.
 *   <li>MCP-provided tools — need the egress proxy for the remote case.
 * </ul>
 */
public final class ToolCatalogue {

    private static final Map<String, ToolDefinition> BY_NAME = index(
            // from_line/max_lines are optional and are the single most important thing this
            // catalogue offers. Without them the only way to see part of a large file is to read all
            // of it and have the middle cut out -- which hands a model a mangled view it cannot then
            // ask a better question about. Narrowing (outline, slice, read) is how an unfamiliar
            // repository gets explored at all, and a read tool that cannot slice makes every step of
            // that cost a whole file.
            new ToolDefinition(
                    "read_file",
                    "Read a file, or a range of its lines. Give from_line and max_lines to read part "
                            + "of a large file instead of all of it. Output is numbered.",
                    List.of("path"),
                    RiskLevel.LOW,
                    SideEffect.READ,
                    true,
                    Duration.ofSeconds(30)),
            new ToolDefinition(
                    "list_directory",
                    "List what is in one directory of the working copy.",
                    List.of("path"),
                    RiskLevel.LOW,
                    SideEffect.READ,
                    true,
                    Duration.ofSeconds(30)),
            // context and files_only each remove a whole follow-up call. A match with no surrounding
            // lines usually forces a read_file to find out what it means, and a survey of "which
            // files mention this" does not want the lines at all.
            new ToolDefinition(
                    "grep",
                    "Search file contents. Optional: context (lines shown either side of a match), "
                            + "files_only (list matching files instead of lines), path, "
                            + "include_ignored (search files git ignores, off by default).",
                    List.of("pattern"),
                    RiskLevel.LOW,
                    SideEffect.READ,
                    true,
                    Duration.ofSeconds(60)),
            new ToolDefinition(
                    "find_files",
                    "Find files in the working copy whose name matches a glob.",
                    List.of("pattern"),
                    RiskLevel.LOW,
                    SideEffect.READ,
                    true,
                    Duration.ofSeconds(60)),
            new ToolDefinition(
                    "git_diff",
                    "Show what has changed in the working copy so far.",
                    List.of(),
                    RiskLevel.LOW,
                    SideEffect.READ,
                    true,
                    Duration.ofSeconds(60)),
            // The other half of capping output. §15 lists it in the MVP set for exactly this reason:
            // a truncated result the model cannot get behind is data loss wearing a summary's clothes.
            new ToolDefinition(
                    "read_tool_output",
                    "Read more of an earlier result that was too long to show in full. Give the "
                            + "output_id from that result, and optionally from_line and max_lines.",
                    List.of("output_id"),
                    RiskLevel.LOW,
                    SideEffect.READ,
                    true,
                    Duration.ofSeconds(30)),
            new ToolDefinition(
                    "git_log",
                    "Show recent commits.",
                    List.of(),
                    RiskLevel.LOW,
                    SideEffect.READ,
                    true,
                    Duration.ofSeconds(30)),
            // MEDIUM, not LOW, and not because a sandbox write is dangerous -- it is contained and the
            // sandbox is destroyed either way. It is because §17 reads the diff these produce, and a
            // rating that said LOW would be saying the change itself is uninteresting.
            new ToolDefinition(
                    "write_file",
                    "Replace one file's contents. Prefer apply_patch; use this for new files.",
                    List.of("path", "content"),
                    RiskLevel.MEDIUM,
                    SideEffect.WRITE_SANDBOX,
                    false,
                    Duration.ofSeconds(30)),
            // §15 prefers this over write_file: a unified diff fails loudly when the model's
            // assumption about the file is wrong, where a whole-file write silently clobbers.
            // A rejected patch is a signal worth having, not an inconvenience worth designing away.
            new ToolDefinition(
                    "apply_patch",
                    "Apply a unified diff to the working copy. Fails if the file is not as expected.",
                    List.of("patch"),
                    RiskLevel.MEDIUM,
                    SideEffect.WRITE_SANDBOX,
                    false,
                    Duration.ofSeconds(60)),
            // argv OR script, and neither is required on its own -- validated in the dispatch, because
            // "exactly one of these two" is not something a required-arguments list can express.
            //
            // The shell is here because §15's ban was withdrawn: it rested on the claim that a shell
            // makes the sandbox's other controls decorative, and that was measured and found false.
            // An agent that cannot pipe, chain, or redirect cannot do ordinary work, and the
            // allowlist it was protecting never contained an adversary anyway. What replaces the
            // restriction is recording -- see CommandSignals.
            new ToolDefinition(
                    "run_command",
                    "Run a command in the working copy. Give argv for a single command, or script for "
                            + "a shell line with pipes, redirection and && chaining.",
                    List.of(),
                    RiskLevel.MEDIUM,
                    SideEffect.EXEC,
                    false,
                    Duration.ofMinutes(10)));

    private ToolCatalogue() {}

    /** Every tool there is. */
    public static Set<String> names() {
        return BY_NAME.keySet();
    }

    public static Optional<ToolDefinition> find(String name) {
        return Optional.ofNullable(BY_NAME.get(name));
    }

    /**
     * The definitions for a set of names, in catalogue order.
     *
     * <p>Silently drops names it does not know, because this composes what is <em>offered</em> and an
     * unknown name there is a configuration mistake rather than an attack. The security-relevant
     * check is the other one, at dispatch, where an unknown name is a model inventing a tool and is
     * refused loudly.
     */
    public static List<ToolDefinition> offer(Set<String> names) {
        return BY_NAME.values().stream()
                .filter(definition -> names.contains(definition.name()))
                .toList();
    }

    private static Map<String, ToolDefinition> index(ToolDefinition... definitions) {
        var byName = new LinkedHashMap<String, ToolDefinition>();
        for (ToolDefinition definition : definitions) {
            if (byName.put(definition.name(), definition) != null) {
                throw new IllegalStateException("two tools called " + definition.name());
            }
        }
        return Map.copyOf(byName);
    }
}
