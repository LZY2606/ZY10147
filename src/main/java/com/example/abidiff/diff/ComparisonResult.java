package com.example.abidiff.diff;

import java.util.List;

/** Stored comparison row plus its findings and identity metadata. */
public record ComparisonResult(String id, String leftSnapshotId, String rightSnapshotId,
                               String platform, String arch, String rulesetId,
                               String leftRelease, String rightRelease,
                               List<Finding> findings, int revision) {
}
