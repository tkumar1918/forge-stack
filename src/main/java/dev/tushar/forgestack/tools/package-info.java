/**
 * Everything a model is allowed to do, and the one place that decides whether it may.
 *
 * <p>§15 opens by saying tools are the <em>only</em> way a model affects the world, which makes this
 * package the security perimeter's inner half — {@code sandbox} contains what runs, and this decides
 * what is asked of it. Neither is sufficient alone: the sandbox does not know which of its
 * capabilities this attempt was offered, and this module cannot contain anything by itself.
 *
 * <p><strong>One class dispatches every call, and that is deliberate.</strong> The obvious design is
 * a {@code Tool} interface with an implementation per tool, and it was not chosen. §15's pipeline —
 * resolve, validate, authorise, dedupe, execute, persist, truncate — has to run identically for
 * every call, and an interface per tool is exactly the shape that lets one of them quietly skip a
 * step. The same reasoning the state machine's transition table gives: safety here comes from
 * concentration in one reviewed place, not from extensibility. Adding a tool should mean editing a
 * table a reviewer can read top to bottom.
 *
 * <p>Depends on {@code sandbox} and on {@code platform}, and on nothing that holds a credential. A
 * tool cannot leak a token it has no way to reach, which is stronger than remembering not to pass
 * one.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Tools",
        allowedDependencies = {"platform", "sandbox"})
package dev.tushar.forgestack.tools;
