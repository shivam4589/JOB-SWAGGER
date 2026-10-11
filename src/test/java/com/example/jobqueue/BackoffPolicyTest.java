package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.jobqueue.worker.BackoffPolicy;
import org.junit.jupiter.api.Test;

class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy(2, 300);

    @Test
    void delayDoublesWithEachFailedAttempt() {
        assertThat(policy.baseDelaySeconds(1)).isEqualTo(2);
        assertThat(policy.baseDelaySeconds(2)).isEqualTo(4);
        assertThat(policy.baseDelaySeconds(3)).isEqualTo(8);
        assertThat(policy.baseDelaySeconds(8)).isEqualTo(256);
    }

    @Test
    void delayIsCappedAtMax() {
        assertThat(policy.baseDelaySeconds(9)).isEqualTo(300);
        assertThat(policy.baseDelaySeconds(100)).isEqualTo(300);
    }

    @Test
    void jitterStaysWithinTwentyFivePercent() {
        for (int i = 0; i < 1000; i++) {
            assertThat(policy.delaySeconds(3)).isBetween(6.0, 10.0);
        }
    }
}
