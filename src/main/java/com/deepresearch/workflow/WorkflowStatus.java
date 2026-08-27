package com.deepresearch.workflow;

import java.util.EnumSet;
import java.util.Set;

/** Public, durable workflow states. The Python runner and Java control plane share these names. */
public enum WorkflowStatus {
    QUEUED,
    PLANNING,
    WORKING,
    REVIEWING,
    SYNTHESIZING,
    FINALIZING,
    SUCCEEDED,
    INSUFFICIENT_EVIDENCE,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    BUDGET_EXCEEDED;

    private static final Set<WorkflowStatus> TERMINAL = EnumSet.of(
            SUCCEEDED, INSUFFICIENT_EVIDENCE, FAILED, CANCELLED, TIMED_OUT, BUDGET_EXCEEDED);

    public boolean terminal() {
        return TERMINAL.contains(this);
    }
}
