package com.example.abidiff.rules;

import com.example.abidiff.model.Enums.Boundary;
import com.example.abidiff.model.Enums.Severity;

import java.util.Map;

/**
 * A revisioned platform/arch rule set.
 *
 * <p>Conclusions depend on platform, architecture and the PUBLIC/PRIVATE
 * boundary. Multiple rule sets for the same platform/arch coexist (new
 * revisions are added, old ones never deleted), so any historical comparison
 * can be reproduced.
 */
public record RuleSet(String id, String platform, String arch, int revision,
                      boolean active, String note,
                      Map<Boundary, Map<String, Severity>> rules) {

    public Severity severity(Boundary boundary, String changeKind, Severity fallback) {
        Map<String, Severity> perKind = rules.get(boundary);
        if (perKind == null) {
            return fallback;
        }
        return perKind.getOrDefault(changeKind, fallback);
    }
}
