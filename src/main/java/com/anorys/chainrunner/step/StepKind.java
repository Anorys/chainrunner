package com.anorys.chainrunner.step;

import com.anorys.chainrunner.config.StepFields;

/**
 * A type of step: what {@code type:} names in the configuration. It reads the fields of its own steps; the
 * fields every step shares ({@code name}, {@code forEach}, {@code extract}, {@code skipIf} / {@code onlyIf},
 * {@code database}, {@code enabled}) are read before it. Each kind is listed in {@link StepKinds}.
 */
public interface StepKind {

    /** What {@code type:} says for this kind: {@code http}, {@code poll}. */
    String type();

    /** Reads one step; a missing or wrong field throws a {@link com.anorys.chainrunner.config.ConfigException}. */
    Step parse(StepFields fields);
}
