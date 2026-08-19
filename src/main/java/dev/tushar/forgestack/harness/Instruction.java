package dev.tushar.forgestack.harness;

/**
 * One thing to do, and the ceiling it may spend doing it.
 *
 * <p>Deliberately not a conversation. Both candidate harnesses accumulate chat history inside the
 * session, and §13 records where that ends: a growing prompt that reaches two hundred thousand
 * tokens and never comes back. ForgeStack assembles each instruction from persisted state, so what
 * the model sees is a decision the context assembler made and can be replayed, rather than a
 * transcript that grew.
 *
 * @param text     what to do, assembled by the caller — repository content inside it is fenced as
 *                 data by the assembler, never delivered as instructions (§17)
 * @param maxSteps the ceiling for this instruction alone, which is not the attempt's ceiling; a
 *                 single instruction that burns the whole attempt budget is a bug this bounds
 */
public record Instruction(String text, int maxSteps) {

    public Instruction {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("an instruction has to say something");
        }
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("an instruction needs a positive step ceiling");
        }
    }
}
