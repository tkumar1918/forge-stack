package dev.tushar.forgestack.diffguard;

/**
 * One thing wrong with a diff, and where.
 *
 * @param guard  which check fired
 * @param path   the file it fired on
 * @param detail what was seen, quoted or described — this ends up in front of a person deciding
 *     whether the agent was being helpful or being gamed, so it has to be specific enough to judge
 */
public record DiffFinding(DiffGuard guard, String path, String detail) {

    @Override
    public String toString() {
        return "%s in %s: %s".formatted(guard, path, detail);
    }
}
