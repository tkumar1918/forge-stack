package dev.tushar.forgestack.sandbox;

/**
 * Whether a sandbox is still there.
 *
 * <p>Asked rather than assumed, because §20 requires that losing one is routine: pods are evicted,
 * nodes drain, and containers are OOM-killed. A runtime that only worked because containers rarely
 * vanish would break the day it met a busier substrate.
 */
public enum HealthState {
    ALIVE,
    /** Reachable, but something is wrong — out of disk, or the process died inside it. */
    DEGRADED,
    GONE
}
