package com.anorys.chainrunner.step;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every type of step chainrunner knows. Listed here rather than found at run time: the native executable
 * only holds the classes it was built with, so a new kind of step is a class added to this list.
 */
public final class StepKinds {

    /** The type of a step written without {@code type:}. */
    public static final String DEFAULT = "http";

    private static final List<StepKind> ALL = List.of(new HttpStep.Kind(), new PollStep.Kind());

    private StepKinds() {
    }

    public static Optional<StepKind> find(String type) {
        String wanted = type.strip().toLowerCase(Locale.ROOT);
        return ALL.stream().filter(k -> k.type().equals(wanted)).findFirst();
    }

    /** {@code http, poll}: for error messages. */
    public static List<String> types() {
        return ALL.stream().map(StepKind::type).toList();
    }
}
