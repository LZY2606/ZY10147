package com.example.abidiff.diff;

import com.example.abidiff.model.Enums.Category;
import com.example.abidiff.model.Enums.Severity;

import java.util.List;
import java.util.Map;

/** One atomic ABI observation produced by the diff engine. */
public record Finding(
        String componentStableId,
        String symbolStableId,
        String kind,
        Category category,
        Severity severity,
        String boundary,
        String title,
        Map<String, Object> detail,
        List<String> affectedRoots) {
}
