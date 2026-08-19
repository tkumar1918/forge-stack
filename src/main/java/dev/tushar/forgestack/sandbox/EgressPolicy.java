package dev.tushar.forgestack.sandbox;

/** What a sandbox may reach. Declared here, translated by the adapter into whatever its substrate uses. */
public enum EgressPolicy {

    /** Nothing leaves. The default, and the only correct setting for a repository nobody has vetted. */
    DENY_ALL,

    /**
     * Package registries and the model provider, through ForgeStack's proxy, and nothing else.
     *
     * <p>The model provider is on this list because the agent loop needs one and must not hold the
     * key: the proxy holds the real credential and attaches it, so a compromised sandbox has a
     * per-attempt token worth nothing anywhere else.
     */
    PROXY_ONLY
}
