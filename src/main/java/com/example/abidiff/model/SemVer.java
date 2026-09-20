package com.example.abidiff.model;

/** Minimal numeric major.minor.patch comparator (no pre-release metadata). */
public record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

    public static SemVer parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty version");
        }
        String t = text.trim().replaceFirst("^[vV]", "");
        String[] parts = t.split("\\.");
        if (parts.length == 0 || parts.length > 3) {
            throw new IllegalArgumentException("bad semver: " + text);
        }
        int major = Integer.parseInt(parts[0]);
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        return new SemVer(major, minor, patch);
    }

    public boolean within(SemVer fromInclusive, SemVer toInclusive) {
        return compareTo(fromInclusive) >= 0 && compareTo(toInclusive) <= 0;
    }

    @Override
    public int compareTo(SemVer o) {
        int c = Integer.compare(major, o.major);
        if (c != 0) return c;
        c = Integer.compare(minor, o.minor);
        if (c != 0) return c;
        return Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
