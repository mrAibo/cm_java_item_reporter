package com.mraibo.cminsight.statistics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-repository-context cache of targeted single-ItemType detail results, keyed by ItemType identity.
 *
 * <h2>Not a statistics cache</h2>
 *
 * <p>This holds no full {@link StatisticsSnapshot}, no totals and no coverage: it cannot answer a
 * dashboard question, and nothing here is ever summed. It holds at most one {@link TargetedItemTypeDetail}
 * per ItemType id, each with its own capture instant and its own database anchor, and it is deliberately a
 * different object from the coordinator's published snapshot.
 *
 * <h2>Owned by one context, discarded with it</h2>
 *
 * <p>One instance belongs to one {@code RepositoryContext} and is discarded when that context closes, so a
 * detail measured against a previous repository's database can never be served to a new one. A context
 * switch therefore does not "migrate" or "carry over" detail data: it drops it.
 *
 * <h2>Bounded by construction, with one defensive cap</h2>
 *
 * <p>The key space is the active repository's ItemType ids - the targeted path resolves the id from the
 * metadata list before it publishes - so the map cannot exceed that list. {@link #MAX_DETAILS} is a second,
 * cheap bound for a future caller that publishes an id the resolver never validated; a publish beyond it
 * fails and is reported by the caller instead of growing an unbounded map.
 *
 * <h2>Thread safety</h2>
 *
 * <p>{@link ConcurrentHashMap} gives one atomic publish per key, and every read walks a copied list, so a
 * reader sees a consistent entry and never a partially filled object. Publishing the same ItemType twice
 * replaces that ItemType's entry; it never accumulates history for it.
 */
public final class TargetedDetailCache {

    /** Defensive ceiling on distinct ItemType ids; see the class javadoc for why this is not the real bound. */
    public static final int MAX_DETAILS = 20_000;

    /** Deterministic read order for a same-instant change; also the list order of {@link #all()}. */
    private static final Comparator<TargetedItemTypeDetail> BY_ITEM_TYPE_ID =
            Comparator.comparingInt(TargetedItemTypeDetail::itemTypeId);

    private final ConcurrentHashMap<Integer, TargetedItemTypeDetail> details = new ConcurrentHashMap<>();

    /**
     * The latest published detail for one ItemType, or empty when none was measured in this context.
     *
     * <p>Empty is the honest answer for both "never refreshed" and "the last attempt failed": a failed
     * attempt publishes nothing (see {@link TargetedRefreshResult}), so it cannot make a previous
     * measurement disappear and cannot appear here as a result with no number.
     */
    public Optional<TargetedItemTypeDetail> find(int itemTypeId) {
        return Optional.ofNullable(details.get(itemTypeId));
    }

    /** Every published detail, in ItemType id order. A copy: the caller cannot mutate the cache. */
    public List<TargetedItemTypeDetail> all() {
        List<TargetedItemTypeDetail> copy = new ArrayList<>(details.values());
        copy.sort(BY_ITEM_TYPE_ID);
        return List.copyOf(copy);
    }

    /**
     * Publishes one detail for its ItemType id, replacing any previous entry for that id.
     *
     * @return true when the entry was stored; false when this id would exceed {@link #MAX_DETAILS}
     */
    public boolean publish(TargetedItemTypeDetail detail) {
        Objects.requireNonNull(detail, "detail");
        int itemTypeId = detail.itemTypeId();
        TargetedItemTypeDetail existing = details.get(itemTypeId);
        if (existing != null) {
            // A replace never grows the map, so the cap below cannot be reached by refreshing.
            details.put(itemTypeId, detail);
            return true;
        }
        if (!putIfWithinCapacity(itemTypeId, detail)) {
            return false;
        }
        return true;
    }

    /**
     * Inserts a new key only while the cache is below its cap, with the size check and the insert in one
     * {@code compute} so two concurrent publishes cannot both pass the bound.
     */
    private boolean putIfWithinCapacity(int itemTypeId, TargetedItemTypeDetail detail) {
        boolean[] stored = new boolean[1];
        details.compute(itemTypeId, (key, current) -> {
            if (current != null || details.size() < MAX_DETAILS) {
                stored[0] = true;
                return detail;
            }
            return null;
        });
        return stored[0];
    }

    /**
     * Removes one ItemType's detail.
     *
     * @return the detail that was removed, or empty when the id had none
     */
    public Optional<TargetedItemTypeDetail> discard(int itemTypeId) {
        return Optional.ofNullable(details.remove(itemTypeId));
    }

    /**
     * Removes every detail.
     *
     * <p>Called when the owning context closes: the details belong to that context's database and are not
     * transferable to the next one.
     */
    public void discardAll() {
        details.clear();
    }

    /** How many ItemTypes currently have a published detail. */
    public int size() {
        return details.size();
    }

    /** True when no ItemType has a published detail in this context. */
    public boolean isEmpty() {
        return details.isEmpty();
    }

    @Override
    public String toString() {
        return "TargetedDetailCache[details=" + details.size() + "/" + MAX_DETAILS + "]";
    }
}
