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

    private final SandboxProvider sandboxes;

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
                yield capped(false, new String(content, StandardCharsets.UTF_8));
            }
            case "list_directory" -> run(sandbox, definition, "ls", List.of("-la", call.text("path")));
            // -r to recurse, -n for line numbers, -I to skip binaries. The pattern is a model's string
            // and is passed as one argument, so it cannot become a second flag or a second command.
            // grep exits 0 for a match, 1 for no match, and 2 for a real error, so "not zero" is the
            // wrong test. A model told its search *failed* retries the search; a model told the search
            // found nothing looks somewhere else, and only one of those is progress. Caught by a test
            // that was originally written weakly enough to pass while this was wrong.
            case "grep" -> run(
                    sandbox,
                    new ExecRequest(
                            "grep",
                            List.of("-rnI", "--", call.text("pattern"), call.textOr("path", ".")),
                            ".",
                            definition.timeout()),
                    exitCode -> exitCode > 1);
            case "find_files" -> run(
                    sandbox, definition, "find", List.of(".", "-name", call.text("pattern"), "-type", "f"));
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

    private static ToolResult capped(boolean failed, String output) {
        return capped(failed, output, output.getBytes(StandardCharsets.UTF_8).length);
    }

    private static ToolResult capped(boolean failed, String output, long totalBytes) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return new ToolResult(failed, output, totalBytes, false);
        }
        // Both ends. A build log's cause is at the top and its verdict is at the bottom, and keeping
        // only the head is how a model reads a compilation error and never learns the suite failed.
        int half = MAX_OUTPUT_CHARS / 2;
        String kept = output.substring(0, half) + "%n%n[... %d characters omitted ...]%n%n".formatted(
                        output.length() - MAX_OUTPUT_CHARS)
                + output.substring(output.length() - half);
        return new ToolResult(failed, kept, totalBytes, true);
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
        return Set.of("ls", "grep", "find", "git");
    }
}
