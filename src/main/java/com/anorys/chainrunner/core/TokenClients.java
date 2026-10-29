package com.anorys.chainrunner.core;

import com.anorys.chainrunner.config.ChainRunnerConfig.Api;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;

/** The tokens of an environment: one per API. */
public final class TokenClients {

    private final HttpClient http;
    private final Environment env;
    private final Duration timeout;
    private final Clock clock;
    private final Map<String, TokenClient> clients = new LinkedHashMap<>();

    public TokenClients(HttpClient http, Environment env, Duration timeout, Clock clock) {
        this.http = http;
        this.env = env;
        this.timeout = timeout;
        this.clock = clock;
    }

    /** The token client of {@code api}. */
    public synchronized TokenClient of(Api api) {
        return clients.computeIfAbsent(api.name(), n -> new TokenClient(http, api.auth(), timeout, clock));
    }

    /** The token clients of the environment's APIs that use a token, by API name. */
    public Map<String, TokenClient> all() {
        Map<String, TokenClient> out = new LinkedHashMap<>();
        for (Api api : env.apis().values()) {
            if (api.auth().enabled()) {
                out.put(api.name(), of(api));
            }
        }
        return out;
    }

    /** The token clients the HTTP steps of {@code calls} use, each once (an unknown API is left for the run to report). */
    public List<TokenClient> forCalls(List<Planner.PlannedCall> calls) {
        Set<TokenClient> used = new LinkedHashSet<>();
        for (Planner.PlannedCall c : calls) {
            if (!c.call().step().sendsToken()) {
                continue;
            }
            try {
                TokenClient client = of(env.api(c.call().step().api()));
                if (client.auth().enabled()) {
                    used.add(client);
                }
            } catch (IllegalArgumentException e) {
                // Reported by the run, on that step.
            }
        }
        return List.copyOf(used);
    }

    /** Typed passwords that a server refused are asked for again (those set in the configuration stay). */
    public synchronized void forgetTypedPasswords() {
        clients.forEach((name, client) -> {
            if (env.api(name).auth().needsPassword() && client.current() == null) {
                client.password("");
            }
        });
    }

    public Environment environment() {
        return env;
    }
}
