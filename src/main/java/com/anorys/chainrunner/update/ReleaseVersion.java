package com.anorys.chainrunner.update;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A chainrunner version: {@code X.Y.Z}, optionally with a qualifier ({@code 0.2.0-SNAPSHOT}). A version with a
 * qualifier comes before the same version without one. Tags may carry a leading {@code v}.
 */
public record ReleaseVersion(int major, int minor, int patch, String qualifier) implements Comparable<ReleaseVersion> {

    private static final Pattern FORMAT = Pattern.compile("v?(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?");

    private static final Comparator<ReleaseVersion> ORDER = Comparator.comparingInt(ReleaseVersion::major)
            .thenComparingInt(ReleaseVersion::minor)
            .thenComparingInt(ReleaseVersion::patch)
            .thenComparing(ReleaseVersion::qualifier, Comparator.nullsLast(Comparator.naturalOrder()));

    public static Optional<ReleaseVersion> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = FORMAT.matcher(text.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new ReleaseVersion(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)), matcher.group(4)));
    }

    /** A build from a release ({@code 0.2.0}), as opposed to a development build ({@code 0.2.0-SNAPSHOT}). */
    public boolean isRelease() {
        return qualifier == null;
    }

    public boolean isNewerThan(ReleaseVersion other) {
        return compareTo(other) > 0;
    }

    @Override
    public int compareTo(ReleaseVersion other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (qualifier == null ? "" : "-" + qualifier);
    }
}
