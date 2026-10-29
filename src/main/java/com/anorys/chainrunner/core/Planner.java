package com.anorys.chainrunner.core;

import com.anorys.chainrunner.config.ChainRunnerConfig;
import com.anorys.chainrunner.config.ChainRunnerConfig.Call;
import com.anorys.chainrunner.config.ChainRunnerConfig.Environment;
import com.anorys.chainrunner.config.ChainRunnerConfig.Sequence;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a sequence into the list of steps to make: disabled steps are left out and each {@code forEach}
 * (of the sequence or of a step) becomes one step per value (per combination when it has several
 * variables).
 */
public final class Planner {

    private Planner() {
    }

    /**
     * The variables of a run, the later ones winning: the global {@code variables}, the environment's,
     * then {@code overrides} (command line or edited in the UI). Built in: {@code env} (the environment's
     * name), {@code today} ({@code 2026-10-28}) and {@code now} ({@code 2026-10-28T14:05:00}).
     */
    public static Map<String, Object> variables(ChainRunnerConfig config, Environment env, Map<String, Object> overrides) {
        Map<String, Object> vars = new LinkedHashMap<>(config.variables());
        vars.putAll(env.variables());
        vars.putAll(overrides);
        vars.put("env", env.name());
        LocalDateTime now = LocalDateTime.now();
        vars.putIfAbsent("today", now.toLocalDate().toString());
        vars.putIfAbsent("now", now.withNano(0).toString());
        return vars;
    }

    /**
     * The databases {@code calls} query (poll steps and guards), each once, in order of first use.
     *
     * @throws IllegalArgumentException when a step names a database the environment lacks, or names
     *                                  none when it has several
     */
    public static List<ChainRunnerConfig.Database> databases(Environment env, List<PlannedCall> calls) {
        Map<String, ChainRunnerConfig.Database> used = new LinkedHashMap<>();
        for (PlannedCall planned : calls) {
            Call call = planned.call();
            if (call.step().usesDatabase()) {
                ChainRunnerConfig.Database db = env.database(call.database());
                used.putIfAbsent(db.name(), db);
            }
            if (call.guard() != null) {
                ChainRunnerConfig.Database db = env.database(call.guard().database() != null ? call.guard().database() : call.database());
                used.putIfAbsent(db.name(), db);
            }
        }
        return List.copyOf(used.values());
    }

    /** The variables set in the configuration or by the user, without the built-in ones: what the UI lists. */
    public static Map<String, Object> userVariables(ChainRunnerConfig config, Environment env, Map<String, Object> overrides) {
        Map<String, Object> vars = new LinkedHashMap<>(config.variables());
        vars.putAll(env.variables());
        vars.putAll(overrides);
        return vars;
    }

    /**
     * The steps to make, in order. A sequence {@code forEach} runs its steps for each value: every step for
     * all the values before the next step ({@code order: phases}, the default: all the export calls, then all
     * the polls, then all the downloads), or all the steps for one value before the next value
     * ({@code order: each}). A step's own {@code forEach} repeats it within that.
     */
    public static List<PlannedCall> plan(Sequence sequence, Map<String, Object> vars) {
        List<Call> steps = sequence.calls().stream().filter(Call::enabled).toList();
        List<Map<String, Object>> outer = combinations(sequence.forEach(), vars);
        List<PlannedCall> planned = new ArrayList<>();
        if (sequence.order() == ChainRunnerConfig.Order.EACH) {
            for (Map<String, Object> o : outer) {
                for (Call call : steps) {
                    add(planned, call, o, vars);
                }
            }
        } else {
            for (Call call : steps) {
                for (Map<String, Object> o : outer) {
                    add(planned, call, o, vars);
                }
            }
        }
        return planned;
    }

    private static void add(List<PlannedCall> planned, Call call, Map<String, Object> outer, Map<String, Object> vars) {
        Map<String, Object> base = new LinkedHashMap<>(vars);
        base.putAll(outer);
        for (Map<String, Object> inner : combinations(call.forEach(), base)) {
            Map<String, Object> loop = new LinkedHashMap<>(outer);
            loop.putAll(inner);
            Map<String, Object> scope = new LinkedHashMap<>(vars);
            scope.putAll(loop);
            planned.add(new PlannedCall(planned.size(), Template.preview(call.name(), scope), call, Map.copyOf(loop)));
        }
    }

    /** Every combination of the {@code forEach} values, in order; one empty combination without forEach. */
    static List<Map<String, Object>> combinations(Map<String, Object> forEach, Map<String, Object> vars) {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        for (Map.Entry<String, Object> entry : forEach.entrySet()) {
            List<Object> values = values(entry.getValue(), vars);
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> combination : result) {
                for (Object value : values) {
                    Map<String, Object> copy = new LinkedHashMap<>(combination);
                    copy.put(entry.getKey(), value);
                    next.add(copy);
                }
            }
            result = next;
        }
        return result;
    }

    /** A list, or a text: a {@code {{variable}}} holding a list, or values split by commas or spaces. */
    private static List<Object> values(Object raw, Map<String, Object> vars) {
        Object value = raw instanceof String s ? Template.value(s, vars) : raw;
        List<Object> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            out.addAll(list);
        } else if (value instanceof String s) {
            out.addAll(split(s));
        } else if (value != null) {
            out.add(value);
        }
        return out;
    }

    /** Values split by commas, semicolons or spaces. */
    public static List<String> split(String s) {
        return Arrays.stream(s.split("[,;\\s]+")).map(String::strip).filter(v -> !v.isEmpty()).toList();
    }

    /** One call to make: its place in the run, its name as shown, and its {@code forEach} values. */
    public record PlannedCall(int index, String name, Call call, Map<String, Object> loop) {
    }
}
