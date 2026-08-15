package dev.tushar.forgestack.platform;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an interface as a deliberate abstraction seam.
 *
 * <p>ForgeStack's default is <em>not</em> to introduce interfaces. An abstraction must answer to a
 * pressure that exists today, not an anticipated one. At least one of these must be true:
 *
 * <ol>
 *   <li>A second implementation exists, or a test genuinely cannot be written without a seam.
 *   <li>The boundary is on the extraction list (agent runtime, execution harness).
 *   <li>A guarantee must be enforced mechanically rather than by discipline.
 * </ol>
 *
 * <p>Enforced by {@code AbstractionHygieneTest}: any interface under {@code dev.tushar.forgestack} that
 * is not annotated here, not a Spring Data repository, not {@code sealed}, and does not already
 * have two or more implementations will fail the build.
 *
 * <p>This is not a ban on interfaces. It makes adding one a decision rather than a reflex.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Port {

    /** Why this seam exists — the pressure, in one sentence. */
    String value();
}
