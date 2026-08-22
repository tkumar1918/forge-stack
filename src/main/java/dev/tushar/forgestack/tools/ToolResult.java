package dev.tushar.forgestack.tools;

/**
 * What came back.
 *
 * <p>{@code output} is bounded, and {@code outputBytes} is not: §11 calls unbounded tool output the
 * context-window killer, and it is the heap killer for the process holding the stream. What the model
 * sees is capped; how much there actually was stays recorded, so a truncated result is visibly
 * truncated rather than quietly short.
 *
 * <p><strong>Failure is data here, not an exception.</strong> Failing tests are the ordinary case in
 * this system — the product exists because tests fail — so a non-zero exit is something the runtime
 * reads. Only a call that never happened throws ({@link ToolRefusal}).
 *
 * @param failed      whether the tool reported the work did not succeed
 * @param output      what to show the model, already capped
 * @param outputBytes how much there was in total, before capping
 * @param truncated   whether {@code output} is short of {@code outputBytes}
 */
public record ToolResult(boolean failed, String output, long outputBytes, boolean truncated) {

    static ToolResult succeeded(String output) {
        return new ToolResult(false, output, output.length(), false);
    }

    static ToolResult failed(String output) {
        return new ToolResult(true, output, output.length(), false);
    }
}
