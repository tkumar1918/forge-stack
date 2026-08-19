package dev.tushar.forgestack.harness;

/**
 * What the sandbox may reach, declared rather than configured.
 *
 * <p>Declarative because §16 found that the alternative leaks: a runtime that tells the adapter to
 * attach a network has learned that networks are how egress works, which is true of Docker and false
 * of Kubernetes and of every hosted provider. The adapter translates this into iptables rules, a
 * NetworkPolicy, or a provider API call, and the runtime never learns which.
 */
public enum EgressPolicy {

    /** Nothing leaves. The default, and the only correct setting for a repository we have not vetted. */
    DENY_ALL,

    /**
     * Package registries and the model provider, through ForgeStack's proxy, and nothing else.
     *
     * <p>The model provider is on this list for a reason worth stating plainly, because it is a gap
     * §16 did not anticipate. §16 was written when the sandbox held only tools and the loop ran in
     * Java, so the only credential in question was GitHub's. Every candidate harness co-locates the
     * agent loop with tool execution, which means the loop's LLM API key is inside the sandbox with
     * the customer's source code — the exact exposure §16 refuses for the GitHub token, arriving by
     * a door §16 did not know existed.
     *
     * <p>So the key does not go in. The harness is pointed at this proxy as its model endpoint and
     * given a per-attempt token that is worthless anywhere else; the proxy holds the real credential
     * and attaches it. Both candidate harnesses expose an overridable base URL, so this costs a
     * configuration field rather than a fork.
     */
    PROXY_ONLY
}
