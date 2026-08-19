package dev.tushar.forgestack.harness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Collects a run's events in order, which is what most assertions about a harness are about.
 *
 * <p>Synchronised because {@link ExecutionHarness#run} may be driven from another thread while the
 * test asserts from its own — which is exactly what the pause and mid-run-loss tests do.
 */
final class RecordedEvents implements Consumer<HarnessEvent> {

    private final List<HarnessEvent> events = new ArrayList<>();

    @Override
    public synchronized void accept(HarnessEvent event) {
        events.add(event);
    }

    synchronized List<HarnessEvent> all() {
        return List.copyOf(events);
    }

    synchronized Map<Class<?>, Integer> countsByType() {
        Map<Class<?>, Integer> counts = new HashMap<>();
        for (HarnessEvent event : events) {
            counts.merge(event.getClass(), 1, Integer::sum);
        }
        return counts;
    }
}
