package com.example.abidiff.snapshot;

import com.example.abidiff.decision.DecisionService;
import com.example.abidiff.model.Enums.DecisionKind;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Seeds reviewer decisions for demo comparisons, including an exception that
 * has already expired (valid up to 1.0.x), so the UI demonstrates that an
 * expired exception does not suppress a BREAKING finding at 1.1.0.
 *
 * <p>Decisions go to the separate decision database; nothing here touches the
 * snapshot content.
 */
@Component
public class FixtureDecisionSeeder {

    private final DecisionService decisions;
    private final JdbcTemplate decisionJdbc;
    private final boolean seed;

    public FixtureDecisionSeeder(DecisionService decisions, JdbcTemplate decisionJdbc,
                                 @Value("${abidiff.seed-fixtures:true}") boolean seed) {
        this.decisions = decisions;
        this.decisionJdbc = decisionJdbc;
        this.seed = seed;
    }

    /**
     * Idempotently attach decisions to a linux comparison once it exists.
     * Called from an explicit demo-initialise endpoint (comparisons are created
     * on demand), and safe to invoke repeatedly.
     */
    public void seedForLinuxComparison(String comparisonId, String rulesetId, String rightVersion) {
        if (!seed || alreadySeeded(comparisonId)) {
            return;
        }
        // Expired exception: only valid through 1.0.x; 1.1.0 is outside scope.
        decisions.submit(new DecisionService.SubmitRequest(
                comparisonId, "wgt_log", "linux", DecisionKind.EXCEPTION,
                "legacy varargs tolerance for 1.0.x line only", "fixture",
                "1.0.0", "1.0.255", "1.0.255", rulesetId, 0));
    }

    private boolean alreadySeeded(String comparisonId) {
        Integer n = decisionJdbc.queryForObject(
                "SELECT COUNT(*) FROM resolution WHERE comparison_id=? AND symbol_stable_id='wgt_log'",
                Integer.class, comparisonId);
        return n != null && n > 0;
    }

    public List<Map<String, Object>> countAll() {
        return decisionJdbc.queryForList("SELECT comparison_id, COUNT(*) AS n FROM resolution GROUP BY comparison_id");
    }
}
