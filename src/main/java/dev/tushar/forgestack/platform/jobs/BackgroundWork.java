package dev.tushar.forgestack.platform.jobs;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on the two Spring facilities the background half of the system runs on.
 *
 * <p><strong>Async</strong> because {@code @ApplicationModuleListener} is meta-annotated
 * {@code @Async}, and without it enabled the annotation still compiles, the event still persists,
 * and the listener runs inline — so the outbox would appear to work while quietly holding up every
 * publishing request behind a Redis round trip. Stated here rather than left to autoconfiguration,
 * because the failure mode is invisible.
 *
 * <p><strong>Scheduling</strong> in every role, unconditionally. What a process actually does on a
 * tick is decided by {@link LeaderLock}, not by whether its timers run at all — periodic work that
 * only exists in one role is periodic work nobody exercises until the day it matters, and this way
 * it runs in local development too, where {@code forgestack.role} is {@code all}.
 *
 * <p>Tests keep their intervals long enough never to fire, so a sweep can never overlap an assertion
 * about what a sweep did. See {@code AbstractIntegrationTest}.
 */
@Configuration
@EnableAsync
@EnableScheduling
class BackgroundWork {}
