package com.abi.compare.storage;

import java.util.ArrayList;
import java.util.List;

/** Numeric dotted release ordering ("1.10.2" > "1.9.0"). */
public final class VersionOrder {
    private VersionOrder() {
    }

    public static int compare(String a, String b) {
        if (a == null || b == null) {
            return a == b ? 0 : (a == null ? -1 : 1);
        }
        List<Integer> pa = parts(a);
        List<Integer> pb = parts(b);
        int n = Math.max(pa.size(), pb.size());
        for (int i = 0; i < n; i++) {
            int x = i < pa.size() ? pa.get(i) : 0;
            int y = i < pb.size() ? pb.get(i) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static List<Integer> parts(String v) {
        List<Integer> parts = new ArrayList<>();
        String token = v.replace('-', '.').replace('_', '.');
        for (String p : token.split("\\.")) {
            if (p.isEmpty()) {
                continue;
            }
            int digits = 0;
            while (digits < p.length() && Character.isDigit(p.charAt(digits))) {
                digits++;
            }
            parts.add(digits == 0 ? 0 : Integer.parseInt(p.substring(0, digits)));
        }
        return parts;
    }
}
