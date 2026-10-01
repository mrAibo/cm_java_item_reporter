/*
 * CM Insight - TEST-ONLY compile stub for the proprietary IBM Content Manager 8.7 SDK.
 * Signatures only. Never packaged, never shipped.
 */
package com.ibm.mm.sdk.common;

/**
 * The implementation interface of {@code com.ibm.mm.sdk.server.DKDatastoreICM}, and the parameter
 * type of {@code DKRetrieveOptionsICM.createInstance(...)}.
 *
 * <p>Declared empty on purpose. The real interface declares ~40 members - {@code checkIn},
 * {@code checkOut}, {@code moveObject}, {@code turnOffPool}, the SSL context/socket-factory pairs
 * and the platform feature probes. Every mutating member is absent, and nothing the adapter calls
 * resolves through this interface; leaving it empty means an attempt to reach one of those members
 * through {@code dkDatastoreIntICM} is a compile error rather than a silent write path.
 */
public interface dkDatastoreIntICM extends dkDatastore {
}
