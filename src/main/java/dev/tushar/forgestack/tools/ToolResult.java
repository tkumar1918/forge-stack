package dev.tushar.forgestack.tools;

/**
 * What came back.
 *
 * <p>{@code output} is bounded, and {@code outputBytes} is not: §11 calls unbounded tool output the
 * context-window killer, and it is the heap killer for the process holding the stream. What the model
 * sees is capped; how much there actually was stays recorded, so a truncated result is visibly
 * truncated rather than quietly short.
 *
 * <p><strong>Truncation without {@code outputId} would be data loss.</strong> A capped build log whose
 * middle is simply gone leaves a model reasoning about an extract it cannot get behind, and the usual
 * result is a confident conclusion drawn from the wrong half. The id is the handle
 * {@code read_tool_output} pulls the rest through, which is what makes capping a <em>summary</em>
 * rather than a deletion.
 *
 * <p><strong>Failure is data here, not an exception.</strong> Failing tests are the ordinary case in
 * this system — the product exists because tests fail — so a non-zero exit is something the runtime
 * reads. Only a call that never happened throws ({@link ToolRefusal}).
 *
 * @param failed      whether the tool reported the work did not succeed
 * @param output      what to show the model, already capped
 * @param outputBytes how much there was in total, before capping
 * @param truncated   whether {@code output} is short of {@code outputBytes}
 * @param outputId    handle to the retained full output, or null when nothing was held back
 */
public record ToolResult(boolean failed, String output, long outputBytes, boolean truncated, String outputId) {

    public ToolResult(boolean failed, String output, long outputBytes, boolean truncated) {
        this(failed, output, outputBytes, truncated, null);
    }

    static ToolResult succeeded(String output) {
        return new ToolResult(false, output, output.length(), false, null);
    }

    static ToolResult failed(String output) {
        return new ToolResult(true, output, output.length(), false, null);
    }

    ToolResult retainedAs(String id) {
        return new ToolResult(failed, output, outputBytes, truncated, id);
    }
}
