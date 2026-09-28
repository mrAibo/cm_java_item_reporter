package com.mraibo.cminsight.metadata;

public record ItemTypeInfo(
        String name,
        String description,
        int itemTypeId,
        String classification,
        String versionControl,
        String versioningType,
        String retentionPolicyName) {
}
