package com.mraibo.cminsight.retention;

import java.util.List;

public record RetentionPolicyInfo(
        String name,
        String retentionType,
        boolean retentionEnabled,
        String retentionPeriod,
        boolean expirationEnabled,
        String expirationPeriod,
        String expirationAction,
        List<String> assignedItemTypes) {
    public RetentionPolicyInfo {
        assignedItemTypes = assignedItemTypes == null ? List.of() : List.copyOf(assignedItemTypes);
    }
}
