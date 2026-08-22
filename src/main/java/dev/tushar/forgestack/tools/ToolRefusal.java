package dev.tushar.forgestack.tools;

/**
 * The call was not made, and the model is told why.
 *
 * <p>Distinct from a call that ran and failed, which is a {@link ToolResult} with a non-zero exit.
 * The difference is the whole of §15's first pipeline step: an unknown tool, a missing argument, or
 * a path escaping the workspace must not reach the sandbox at all, and must come back as something
 * the model can read and correct rather than as a stack trace or a silent empty result.
 *
 * <p>The message is written for the model, so it says what was wrong and not what the code was doing.
 */
public class ToolRefusal extends RuntimeException {

    public ToolRefusal(String message) {
        super(message);
    }
}
