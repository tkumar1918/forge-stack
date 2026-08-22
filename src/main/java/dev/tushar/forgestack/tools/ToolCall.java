package dev.tushar.forgestack.tools;

import java.util.List;
import java.util.Map;

/**
 * A model asking for something.
 *
 * <p>Every field is untrusted. The name is untrusted because models invent tool names — §15 requires
 * unknown ones be refused at dispatch and not merely withheld from the offer. The arguments are
 * untrusted because a path in one of them is the shortest route out of the workspace, and because
 * repository content reaches the model as data and comes back out as arguments.
 *
 * <p>Arguments are strings and lists of strings rather than a free-form object graph. A tool that
 * needed richer input than that would be a tool doing too much in one call.
 */
public record ToolCall(String name, Map<String, Object> arguments) {

    public ToolCall {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a tool call names a tool");
        }
        arguments = Map.copyOf(arguments);
    }

    public static ToolCall of(String name, Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("arguments come in pairs");
        }
        var arguments = new java.util.LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            arguments.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return new ToolCall(name, arguments);
    }

    /**
     * One argument as text.
     *
     * @throws ToolRefusal if it is absent or is not text. Refused rather than converted: §15 says
     *     validation failures are rejected and not coerced, and {@code toString()} on whatever
     *     arrived is exactly the coercion it means
     */
    String text(String argument) {
        Object value = arguments.get(argument);
        if (value instanceof String string) {
            return string;
        }
        throw new ToolRefusal("'%s' must be text, and was %s"
                .formatted(argument, value == null ? "absent" : value.getClass().getSimpleName()));
    }

    /** One argument as a list of text, accepting a lone string as a list of one. */
    List<String> textList(String argument) {
        Object value = arguments.get(argument);
        if (value instanceof String string) {
            return List.of(string);
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).toList();
        }
        throw new ToolRefusal("'%s' must be a list of text".formatted(argument));
    }

    /** One optional argument as text, or the given fallback when it is absent. */
    String textOr(String argument, String fallback) {
        return arguments.containsKey(argument) ? text(argument) : fallback;
    }
}
