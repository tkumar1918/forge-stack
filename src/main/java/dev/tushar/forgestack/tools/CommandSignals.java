package dev.tushar.forgestack.tools;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a command appears to be reaching for.
 *
 * <p>This is what §15 means by "commands are parsed, not prefix-matched". It replaced a restriction:
 * the shell is permitted now, and the allowlist is an operational contract rather than a control, so
 * what the plan asks for in exchange is <em>recording</em> — full command text audited, and risk
 * signals raised from what the text actually does rather than from what it starts with.
 *
 * <p><strong>Honest about what this is not.</strong> §15 says "a bash AST". This is a quoting-aware
 * tokeniser with pattern rules over the token stream, which is strictly more than prefix matching and
 * strictly less than a parser. It will miss things a real AST would catch — a signal hidden behind a
 * variable, an interpreter reached through {@code eval}, an obfuscated path — and it is written down
 * that way rather than described as complete.
 *
 * <p><strong>That gap is affordable, and would not be if these were controls.</strong> Nothing here
 * refuses a command. The container is what contains, and it measures identically whether every signal
 * fires or none do (§16, verified against real containers). A missed signal costs visibility. A
 * signal that blocked execution would be a prefix filter with a better name, and the plan is explicit
 * that prefix matching on command text is the approach that does not work.
 *
 * @param found what was noticed
 * @param risk  the rating these imply. §17 takes the higher of this and the model's own claim, and a
 *              model may raise risk and may never lower it
 */
public record CommandSignals(Set<CommandSignal> found, RiskLevel risk) {

    private static final Set<String> INTERPRETERS =
            Set.of("sh", "bash", "zsh", "dash", "ksh", "python", "python2", "python3", "node", "perl", "ruby", "php");

    private static final Set<String> NETWORK_TOOLS = Set.of("curl", "wget", "nc", "ncat", "netcat", "scp", "ftp");

    private static final Set<String> PACKAGE_MANAGERS =
            Set.of("npm", "pnpm", "yarn", "pip", "pip3", "apk", "apt", "apt-get", "gem", "cargo", "go", "brew");

    private static final Set<String> INSTALL_VERBS = Set.of("install", "add", "get", "i");

    /**
     * Credential shapes, by their issuer's own prefix rather than by looking random.
     *
     * <p>Deliberately not "a long string of base64". Commit SHAs, content hashes, base64 test
     * fixtures and UUIDs all look exactly like that, and a signal that fires on every second command
     * is one an operator learns to ignore — which is worse than not having it, because it costs the
     * attention the real ones needed.
     */
    private static final List<Pattern> CREDENTIAL_SHAPES = List.of(
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{16,}"),
            Pattern.compile("github_pat_[A-Za-z0-9_]{20,}"),
            Pattern.compile("sk-[A-Za-z0-9-]{20,}"),
            Pattern.compile("xox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)\\b(password|passwd|secret|token|api[_-]?key)\\s*=\\s*\\S{6,}"));

    private static final Set<String> OPERATORS = Set.of("|", "||", "&&", ";", ">", ">>", "<", "&", "(", ")");

    /** What one shell script appears to do. */
    public static CommandSignals in(String commandText) {
        return from(tokenise(commandText));
    }

    /**
     * What one argument vector appears to do.
     *
     * <p>Read the same way as a script, because argv is not inherently safer — {@code find . -exec sh
     * -c … ;} reaches an interpreter with no shell involved, and a rule that only inspected scripts
     * would be looking at the easier half.
     */
    public static CommandSignals in(List<String> argv) {
        return from(argv);
    }

    private static CommandSignals from(List<String> tokens) {
        Set<CommandSignal> found = EnumSet.noneOf(CommandSignal.class);

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String command = basename(token);
            boolean startsACommand = i == 0 || OPERATORS.contains(tokens.get(i - 1));

            if (NETWORK_TOOLS.contains(command)) {
                found.add(CommandSignal.NETWORK_FETCH);
            }
            // An interpreter is only interesting where a command begins: "python3" as an argument to
            // grep is a search term, and treating it as an execution would make the signal noise.
            if (startsACommand && INTERPRETERS.contains(command) && i > 0) {
                found.add(CommandSignal.PIPE_TO_INTERPRETER);
            }
            if (PACKAGE_MANAGERS.contains(command)
                    && i + 1 < tokens.size()
                    && INSTALL_VERBS.contains(tokens.get(i + 1))) {
                found.add(CommandSignal.PACKAGE_INSTALL);
            }
            if (command.equals("sudo") || command.equals("su") || command.equals("doas")) {
                found.add(CommandSignal.PRIVILEGE_ESCALATION);
            }
            if (command.equals("chmod") && tokens.subList(i, tokens.size()).stream().anyMatch(a -> a.contains("s"))
                    && tokens.subList(i, tokens.size()).stream().anyMatch(a -> a.startsWith("+") || a.contains("4"))) {
                found.add(CommandSignal.PRIVILEGE_ESCALATION);
            }
            if ((token.equals(">") || token.equals(">>")) && i + 1 < tokens.size() && escapes(tokens.get(i + 1))) {
                found.add(CommandSignal.REDIRECT_OUTSIDE_WORKSPACE);
            }
            if (command.equals("rm") && broadDelete(tokens.subList(i, tokens.size()))) {
                found.add(CommandSignal.BROAD_DELETE);
            }
        }

        String whole = String.join(" ", tokens);
        if (CREDENTIAL_SHAPES.stream().anyMatch(shape -> shape.matcher(whole).find())) {
            found.add(CommandSignal.CREDENTIAL_LITERAL);
        }
        return new CommandSignals(Set.copyOf(found), rate(found));
    }

    private static RiskLevel rate(Set<CommandSignal> found) {
        if (found.contains(CommandSignal.PIPE_TO_INTERPRETER)
                || found.contains(CommandSignal.CREDENTIAL_LITERAL)
                || found.contains(CommandSignal.PRIVILEGE_ESCALATION)) {
            return RiskLevel.HIGH;
        }
        return found.isEmpty() ? RiskLevel.LOW : RiskLevel.MEDIUM;
    }

    private static boolean broadDelete(List<String> rest) {
        boolean recursive = rest.stream().anyMatch(a -> a.matches("-[a-zA-Z]*[rR][a-zA-Z]*"));
        boolean wideTarget = rest.stream()
                .skip(1)
                .anyMatch(a -> a.equals("/") || a.equals("*") || a.equals("~") || a.startsWith("/*") || escapes(a));
        return recursive && wideTarget;
    }

    /** Whether a path leaves the workspace, which is where the agent's writes are supposed to stay. */
    private static boolean escapes(String path) {
        return (path.startsWith("/") && !path.startsWith("/workspace")) || path.contains("..");
    }

    private static String basename(String token) {
        int slash = token.lastIndexOf('/');
        return slash < 0 ? token : token.substring(slash + 1);
    }

    /**
     * Splits a command the way a shell would split it, near enough.
     *
     * <p>Quote-aware, because the whole reason prefix matching fails is that {@code "cu""rl"} and
     * {@code 'curl'} are the same command spelled to defeat a string comparison. Operators are
     * separated into their own tokens so that "what starts a command" is answerable.
     */
    static List<String> tokenise(String text) {
        var tokens = new ArrayList<String>();
        var current = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
                continue;
            }
            switch (c) {
                case '\'', '"' -> quote = c;
                case '\\' -> {
                    if (i + 1 < text.length()) {
                        current.append(text.charAt(++i));
                    }
                }
                case ' ', '\t', '\n', '\r' -> flush(tokens, current);
                case '|', '&', ';', '<', '(', ')' -> {
                    flush(tokens, current);
                    // Doubled operators are one token: || and && start commands the same way | does.
                    if (i + 1 < text.length() && text.charAt(i + 1) == c && (c == '|' || c == '&')) {
                        tokens.add(String.valueOf(c) + c);
                        i++;
                    } else {
                        tokens.add(String.valueOf(c));
                    }
                }
                case '>' -> {
                    flush(tokens, current);
                    if (i + 1 < text.length() && text.charAt(i + 1) == '>') {
                        tokens.add(">>");
                        i++;
                    } else {
                        tokens.add(">");
                    }
                }
                default -> current.append(c);
            }
        }
        flush(tokens, current);
        return List.copyOf(tokens);
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }
}
