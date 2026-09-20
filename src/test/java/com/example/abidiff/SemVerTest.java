package com.example.abidiff;

import com.example.abidiff.model.SemVer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemVerTest {
    @Test
    void parsesAndOrdersVersions() {
        assertThat(SemVer.parse("v1.2.3")).isEqualTo(new SemVer(1, 2, 3));
        assertThat(new SemVer(1, 1, 0)).isLessThan(new SemVer(1, 1, 255));
        assertThat(new SemVer(2, 0, 0)).isGreaterThan(new SemVer(1, 99, 99));
    }

    @Test
    void scopeMembershipIsInclusiveAndBounded() {
        SemVer v = SemVer.parse("1.1.0");
        assertThat(v.within(SemVer.parse("1.1.0"), SemVer.parse("1.1.255"))).isTrue();
        assertThat(v.within(SemVer.parse("1.0.0"), SemVer.parse("1.0.255"))).isFalse();
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> SemVer.parse("not-a-version")).isInstanceOf(IllegalArgumentException.class);
    }
}
