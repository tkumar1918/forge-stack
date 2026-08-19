/**
 * Reading a diff for the ways work can be made to look finished without being finished.
 *
 * <p>"Make the tests pass" and "delete the failing test" are indistinguishable to anything measuring
 * outcomes, and every autonomous coding system rediscovers this the hard way. §17 calls this the
 * anti-cheat layer. It is the part of the product that is actually hard to copy: an agent that opens
 * pull requests is a weekend project, and an agent that refuses to call its own work done is not.
 *
 * <p>Nothing here is about intent. A model that disables a flaky test is usually being helpful in the
 * way it was asked to be, and a model that has been prompt-injected looks exactly the same from here.
 * Both produce a diff that must not be accepted quietly, so this reads the diff and nothing else —
 * no rationale, no model opinion, no self-report.
 *
 * <p><strong>Not to be confused with {@code task.TaskGuard}</strong>, and the distinction is what each
 * one reads: {@code TaskGuard} decides a state transition from committed rows, while these read a
 * patch. One of {@code TaskGuard}'s preconditions is that these passed.
 *
 * <p>Pure functions over text, with no database, no Spring, and no network, because the interesting
 * cases are combinations no fixture would produce by accident and they need to be cheap to write.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Diff guards")
package dev.tushar.forgestack.diffguard;
