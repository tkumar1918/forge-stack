package dev.tushar.forgestack.tools;

import dev.tushar.forgestack.sandbox.ExecRequest;
import dev.tushar.forgestack.sandbox.ExecResult;
import dev.tushar.forgestack.sandbox.OutputChunk;
import dev.tushar.forgestack.sandbox.SandboxException;
import dev.tushar.forgestack.sandbox.SandboxHandle;
import dev.tushar.forgestack.sandbox.SandboxProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * Every tool call, through one door.
 *
 * <p>§15's pipeline is <em>resolve, validate, authorise, dedupe, execute, persist, truncate</em>, and
 * the phrase attached to it is "every call, no exceptions". This class is that sentence. It exists as
 * one class rather than one class per tool because a per-tool hierarchy is precisely the shape in
 * which one tool quietly skips a step, and the steps are the whole point.
 *
 * <p><strong>Four of the seven steps run here today. The other three are named, not forgotten:</strong>
 *
 * <ul>
 *   <li><em>Authorise</em> — needs {@code EffectiveAuthorityResolver} (§17, Phase 7). Nothing in this
 *       catalogue reaches GitHub or holds a credential, so there is currently no authority to check
 *       beyond the allowlist. The day a {@code WRITE_GITHUB} tool exists, this becomes load-bearing
 *       and its absence would be a hole rather than a gap.
 *   <li><em>Dedupe</em> — needs the attempt's step number, which lives in {@code task}. A module that
 *       cannot see {@code task} cannot compute the idempotency key, and that dependency is not one to
 *       add casually.
 *   <li><em>Persist</em> — same reason. The runtime records steps from the harness event stream today.
 * </ul>
 *
 * <p>What is not deferred is the part that contains: <strong>the allowlist is checked here and again
 * in the sandbox.</strong> Twice on purpose. §15 requires enforcement at dispatch as well as at offer
 * time because models invent tool names and prompts are edited more often than boundaries are, and
 * the sandbox's own binary check is the backstop for the case where this class is wrong.
 */
public final class ToolDispatch {

    /**
     * What the model is shown of a long result.
     *
     * <p>§11 calls unbounded tool output the context-window killer. A build log is megabytes and a
     * model paying attention to all of it is a model that has no room left for the repository. The
     * full length is reported alongside, so a truncated result is visibly truncated — the failure to
     * avoid is a model reasoning confidently about the first page of a log and never learning there
     * was a second.
     */
    static final int MAX_OUTPUT_CHARS = 32_000;

    /**
     * How much of one over-long result is kept for {@code read_tool_output}.
     *
     * <p>Bounded because this is heap. §11 puts the real answer in blob storage under the workspace's
     * own prefix, which does not exist yet — so a result larger than this is still genuinely lost
     * past the cap, and the model is told so rather than left to discover it by reading a silently
     * short tail.
     */
    static final int MAX_RETAINED_CHARS = 2_000_000;

    private final SandboxProvider sandboxes;

    /**
     * Full outputs, by id, for as long as this dispatch lives.
     *
     * <p>Which is one attempt. Scoped that way rather than globally on purpose: an id from another
     * attempt must not resolve here, because the two may belong to different tenants and a tool that
     * returns another workspace's build log is a cross-tenant read (§18) arriving through a door
     * nobody thought to guard.
     */
    private final java.util.Map<String, String> retained = new java.util.concurrent.ConcurrentHashMap<>();

    public ToolDispatch(SandboxProvider sandboxes) {
        this.sandboxes = sandboxes;
    }

    /**
     * Runs one call, or refuses it.
     *
     * @param offered what this attempt was offered. Frozen at attempt start (§15) and passed in
     *     rather than read from anywhere, so that what is enforced is what was recorded
     * @throws ToolRefusal when the call never happened — unknown tool, withheld tool, missing or
     *     malformed argument, or a path escaping the workspace. Always readable by the model
     */
    public ToolResult dispatch(SandboxHandle sandbox, Set<String> offered, ToolCall call) {
        // 1. Resolve. Unknown and withheld are told apart deliberately: one is a model inventing a
        //    name, the other is a model reaching for something real it was not given this time, and
        //    a model that cannot tell those apart cannot correct itself.
        ToolDefinition definition = ToolCatalogue.find(call.name())
                .orElseThrow(() -> new ToolRefusal("there is no tool called '%s'".formatted(call.name())));
        if (!offered.contains(call.name())) {
            throw new ToolRefusal("'%s' is not available for this attempt".formatted(call.name()));
        }

        // 2. Validate. Rejected, never coerced -- a defaulted argument is the model's mistake being
        //    hidden from the record that exists to show what the model did.
        for (String required : definition.requiredArguments()) {
            if (!call.arguments().containsKey(required)) {
                throw new ToolRefusal("'%s' needs the argument '%s'".formatted(call.name(), required));
            }
        }

        // 5. Execute.
        try {
            return execute(sandbox, definition, call);
        } catch (SandboxException.Refused e) {
            // The sandbox's own path and binary checks. Reaching here means this class let something
            // through that the backstop caught, so it is worth being loud about in a log even though
            // the model just sees a readable refusal.
            throw new ToolRefusal(e.getMessage());
        }
    }

    private ToolResult execute(SandboxHandle sandbox, ToolDefinition definition, ToolCall call) {
        return switch (definition.name()) {
            case "read_file" -> {
                byte[] content = sandboxes.readFile(sandbox, call.text("path"));
                yield slice(
                        new String(content, StandardCharsets.UTF_8),
                        call.numberOr("from_line", 1),
                        call.numberOr("max_lines", Integer.MAX_VALUE));
            }
            case "read_tool_output" -> {
                String id = call.text("output_id");
                String full = retained.get(id);
                if (full == null) {
                    // Refused rather than empty. An id that has fallen out of the store and an id the
                    // model invented look identical from here, and "" reads to a model as "the output
                    // was empty" -- which is a different and much more misleading fact.
                    throw new ToolRefusal("no retained output called '%s'".formatted(id));
                }
                yield slice(full, call.numberOr("from_line", 1), call.numberOr("max_lines", Integer.MAX_VALUE));
            }
            case "list_directory" -> run(sandbox, definition, "ls", List.of("-la", call.text("path")));
            // -r to recurse, -n for line numbers, -I to skip binaries. The pattern is a model's string
            // and is passed as one argument, so it cannot become a second flag or a second command.
            // ripgrep rather than grep, and the difference is not only speed. It skips what git
            // ignores, so a search does not come back with ten thousand hits from build/ and
            // node_modules/ that the model then has to read past. Both exit 1 for "no matches" and 2
            // for a real error, so "not zero" is the wrong failure test either way: a model told its
            // search *failed* retries the search, where a model told it found nothing looks somewhere
            // else. Only one of those is progress. Caught by a test originally written weakly enough
            // to pass while this was wrong.
            case "grep" -> {
                var argv = new java.util.ArrayList<>(List.of("--line-number", "--no-heading", "--color", "never"));
                // Dotfiles are searched -- .github/workflows and .gitignore are repository content an
                // agent legitimately needs -- but .git never is. Verified against a real ripgrep:
                // --no-ignore --hidden walks into .git and buries the answer in loose objects.
                // --no-require-git because ripgrep applies .gitignore only *inside* a git repository
                // by default, so the same search would quietly return vendored and generated files
                // whenever .git happened to be absent -- before the baseline commit, or in a working
                // copy placed rather than cloned. Verified against a real ripgrep. Search behaving
                // differently depending on a directory nobody looked at is worse than either
                // behaviour on its own.
                argv.addAll(List.of("--hidden", "--glob", "!.git", "--no-require-git"));
                if (call.flag("include_ignored")) {
                    argv.add("--no-ignore");
                }
                if (call.flag("files_only")) {
                    argv.add("--files-with-matches");
                }
                int context = call.numberOr("context", 0);
                if (context > 0) {
                    argv.addAll(List.of("--context", String.valueOf(Math.min(context, 20))));
                }
                argv.addAll(List.of("--", call.text("pattern"), call.textOr("path", ".")));
                yield run(
                        sandbox,
                        new ExecRequest("rg", argv, ".", definition.timeout()),
                        exitCode -> exitCode > 1);
            }
            // fd for the same reason as ripgrep: it respects .gitignore, so "where is the config
            // file" does not return four hundred copies out of a dependency directory.
            case "find_files" -> run(
                    sandbox,
                    new ExecRequest(
                            "fd",
                            List.of(
                                    "--type", "f",
                                    "--hidden",
                                    "--exclude", ".git",
                                    // Same reason as grep's, and verified the same way.
                                    "--no-require-git",
                                    "--glob", call.text("pattern")),
                            ".",
                            definition.timeout()),
                    exitCode -> exitCode != 0);
            case "git_diff" -> run(sandbox, definition, "git", List.of("diff"));
            case "git_log" -> run(sandbox, definition, "git", List.of("log", "--oneline", "-n", "20"));
            case "write_file" -> {
                sandboxes.writeFile(
                        sandbox, call.text("path"), call.text("content").getBytes(StandardCharsets.UTF_8));
                yield ToolResult.succeeded("wrote " + call.text("path"));
            }
            // Through stdin rather than a file in the workspace. A patch written to disk first would
            // appear in the diff the §17 guards read, and a guard reading around our own scaffolding
            // is a guard with a hole in it.
            case "apply_patch" -> {
                ExecRequest request = new ExecRequest(
                                "git", List.of("apply", "--verbose", "-"), ".", definition.timeout())
                        .reading(call.text("patch").getBytes(StandardCharsets.UTF_8));
                ToolResult result = run(sandbox, request);
                yield result.failed()
                        // Named as the signal it is. §15: a rejected patch means the model's mental
                        // model of the file has drifted, which is worth surfacing rather than
                        // reporting as a generic non-zero exit it may retry blindly.
                        ? new ToolResult(
                                true,
                                "the patch did not apply -- the file is not what the patch expected:\n" + result.output(),
                                result.outputBytes(),
                                result.truncated())
                        : result;
            }
            case "run_command" -> {
                List<String> argv = call.textList("argv");
                if (argv.isEmpty()) {
                    throw new ToolRefusal("'argv' needs at least the command to run");
                }
                yield run(sandbox, definition, argv.getFirst(), argv.subList(1, argv.size()));
            }
            // Unreachable while the catalogue and this switch agree. It is here because they are two
            // lists that must be edited together, and the failure to avoid is a tool that resolves,
            // validates, and then silently does nothing.
            default -> throw new IllegalStateException("no dispatch for tool " + definition.name());
        };
    }

    private ToolResult run(SandboxHandle sandbox, ToolDefinition definition, String binary, List<String> args) {
        return run(sandbox, new ExecRequest(binary, args, ".", definition.timeout()));
    }

    private ToolResult run(SandboxHandle sandbox, ExecRequest request) {
        return run(sandbox, request, exitCode -> exitCode != 0);
    }

    /**
     * @param isFailure what this command's exit codes mean. Passed in rather than assumed, because
     *     "not zero is failure" is a convention rather than a rule and the tools that break it are
     *     the search tools an agent uses most
     */
    private ToolResult run(SandboxHandle sandbox, ExecRequest request, java.util.function.IntPredicate isFailure) {
        var output = new StringBuilder();
        var bytes = new long[1];
        ExecResult result = sandboxes.exec(sandbox, request, chunk -> {
            bytes[0] += chunk.bytes().length;
            // Still appended past the cap, then trimmed once. Capping inside the sink would need the
            // sink to know how much it has already seen in characters rather than bytes, and a
            // multi-byte character split across two chunks is how that goes wrong quietly.
            if (output.length() < MAX_OUTPUT_CHARS * 2) {
                output.append(new String(chunk.bytes(), StandardCharsets.UTF_8));
            }
        });
        if (result.timedOut()) {
            // Deliberately not a failed result. §16 keeps these apart because they mean different
            // things -- one is the work failing, the other is never having found out -- and a model
            // told "the tests failed" when they timed out will go looking for a bug that is not there.
            return new ToolResult(
                    true,
                    "timed out after %s. Nothing was learned about whether it would have succeeded.%n%s"
                            .formatted(request.timeout(), output),
                    bytes[0],
                    true);
        }
        return capped(isFailure.test(result.exitCode()), output.toString(), bytes[0]);
    }

    /**
     * Part of a text, by line, numbered.
     *
     * <p>Numbered because an unnumbered extract is one a model cannot ask a follow-up question about:
     * it can see the code but not say where it is, and the next call has to re-read the file to find
     * out. The numbers are what make narrowing repeatable rather than a one-way trip.
     */
    private ToolResult slice(String text, int fromLine, int maxLines) {
        if (fromLine < 1) {
            throw new ToolRefusal("'from_line' starts at 1");
        }
        if (maxLines < 1) {
            throw new ToolRefusal("'max_lines' must be at least 1");
        }
        List<String> lines = text.lines().toList();
        if (fromLine > lines.size()) {
            return new ToolResult(
                    false,
                    "the file has %d lines, so there is nothing at line %d".formatted(lines.size(), fromLine),
                    text.length(),
                    false);
        }
        int end = (int) Math.min((long) fromLine - 1 + maxLines, lines.size());
        var numbered = new StringBuilder();
        for (int i = fromLine - 1; i < end; i++) {
            numbered.append(i + 1).append('\t').append(lines.get(i)).append('\n');
        }
        boolean partial = fromLine > 1 || end < lines.size();
        ToolResult result = capped(false, numbered.toString(), numbered.length());
        return partial && !result.truncated()
                // Said out loud. A model handed lines 40-80 with nothing marking them as an extract
                // will reason as though it has seen the file.
                ? new ToolResult(
                        false,
                        "showing lines %d-%d of %d%n%s".formatted(fromLine, end, lines.size(), result.output()),
                        result.outputBytes(),
                        false,
                        result.outputId())
                : result;
    }

    private ToolResult capped(boolean failed, String output) {
        return capped(failed, output, output.getBytes(StandardCharsets.UTF_8).length);
    }

    private ToolResult capped(boolean failed, String output, long totalBytes) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return new ToolResult(failed, output, totalBytes, false);
        }
        // Both ends. A build log's cause is at the top and its verdict is at the bottom, and keeping
        // only the head is how a model reads a compilation error and never learns the suite failed.
        int half = MAX_OUTPUT_CHARS / 2;
        String id = java.util.UUID.randomUUID().toString();
        retained.put(id, output.length() > MAX_RETAINED_CHARS ? output.substring(0, MAX_RETAINED_CHARS) : output);
        String kept = output.substring(0, half)
                + "%n%n[... %d characters omitted -- read_tool_output(output_id: %s) for the rest ...]%n%n"
                        .formatted(output.length() - MAX_OUTPUT_CHARS, id)
                + output.substring(output.length() - half);
        return new ToolResult(failed, kept, totalBytes, true, id);
    }

    /**
     * The binaries this catalogue's tools reach for.
     *
     * <p>A {@code SandboxSpec} that does not permit these gets tools that resolve, validate, and then
     * refuse at the sandbox — which reads to a model like the tool being broken rather than like the
     * attempt being configured wrongly. Exposed so the two lists can be kept together by construction
     * instead of by memory. Does not include whatever {@code run_command} is asked to run: that is
     * the attempt's own decision and the sandbox is what holds it to it.
     */
    public static Set<String> binariesUsed() {
        return Set.of("ls", "rg", "fd", "git");
    }
}
