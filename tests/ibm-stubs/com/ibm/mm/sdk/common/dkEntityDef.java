/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * Base entity definition. {@code retrieveEntity(String)} and {@code listEntities(int)} return this
 * interface, so the adapter must cast to {@code DKItemTypeDefICM} to reach the ItemType getters - the
 * SDK does not narrow the return type.
 *
 * <p>Trimmed to the four read accessors the adapter can read off the interface. The real interface
 * also declares {@code setName}, {@code setType}, {@code setId}, {@code setDescription},
 * {@code isSearchable}, {@code createSubEntity}, {@code add}, {@code del}, {@code deleteSubEntity},
 * the sub-entity and attribute families and {@code clearCache}; none is declared here.
 */
public interface dkEntityDef {

    java.lang.String getName();

    java.lang.String getDescription();

    short getId();

    short getType();
}
