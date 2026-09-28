/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures (and constant values) only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * ICM constants. The real interface declares hundreds of members; this stub carries only the ones
 * the Goal 02 read chain needs to map ICM values to names. Every value below was read from real
 * {@code javap -public -constants} output against cmbicmsdk81.jar - none is guessed.
 *
 * <p>Note the deliberate type differences preserved from the real API:
 * {@code DK_ICM_VERSION_CONTROL_*} are {@code int} while {@code DKItemTypeDefICM.getVersionControl()}
 * returns {@code short}, so a cast is required; the classification and versioning constants are
 * already {@code short}.
 */
public interface DKConstantICM extends DKConstant {

    java.lang.String DK_ICM_ENTITY_TYPE = "ENTITY_TYPE";

    int DK_ICM_BASE = 1;

    int DK_ICM_BASE_AND_VIEW = 3;

    short DK_ICM_ITEMTYPE = 1;

    short DK_ICM_ITEMTYPEVIEW = 2;

    java.lang.String DK_ICM_RELEASE_VERSION = "8.7.0.000";

    java.lang.String DK_ICM_API_VERSION = "0807000000";

    java.lang.String DK_ICM_SYSTEM_HIERARCHICAL_ITEMTYPE_NAME = "ICM$FOLDER";

    int DK_ICM_SYSTEM_HIERARCHICAL_ITEMTYPE_ID = 410;

    short DK_ICM_ITEMTYPE_CLASS_ITEM = 0;

    short DK_ICM_ITEMTYPE_CLASS_RESOURCE_ITEM = 1;

    short DK_ICM_ITEMTYPE_CLASS_DOC_MODEL = 2;

    short DK_ICM_ITEMTYPE_CLASS_DOC_PART = 3;

    int DK_ICM_VERSION_CONTROL_NEVER = 0;

    int DK_ICM_VERSION_CONTROL_ALWAYS = 1;

    int DK_ICM_VERSION_CONTROL_BY_APPLICATION = 2;

    short DK_ICM_DOC_NO_VERSIONING = 0;

    short DK_ICM_ITEM_VERSIONING_OPTIMIZED = 1;

    short DK_ICM_ITEM_VERSIONING_FULL = 2;
}
