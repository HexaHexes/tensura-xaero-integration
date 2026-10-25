package org.bitwisemadness.tensuraxaerointegration.refresh;

import java.util.ArrayList;
import java.util.List;

/**
 * A rotating cursor that hands out a bounded slice of a rotating list.
 *
 * <p>Periodic reconciliation must not read every subscribed chunk in one tick, so
 * each cycle takes at most {@code budget} entries and remembers where it stopped.
 * Over successive cycles every entry is visited, and a list that grows or
 * shrinks between calls cannot make the cursor point outside it.</p>
 *
 * @param <K> the entry type
 */
public final class ReconcileCursor<K> {

    private int index;

    /**
     * The next slice of at most {@code budget} entries, starting where the last
     * call stopped.
     *
     * <p>A budget larger than the list returns the whole list once; the cursor
     * then wraps to the start rather than visiting entries twice in one call.</p>
     */
    public List<K> next(List<K> values, int budget) {
        if (values.isEmpty() || budget <= 0) {
            return List.of();
        }
        int size = values.size();
        int effective = Math.min(budget, size);
        List<K> slice = new ArrayList<>(effective);
        for (int i = 0; i < effective; i++) {
            if (this.index >= size) {
                this.index = 0;
            }
            slice.add(values.get(this.index));
            this.index++;
        }
        if (this.index >= size) {
            this.index = 0;
        }
        return slice;
    }

    /** Restarts the sweep from the beginning, e.g. after a subscription reset. */
    public void reset() {
        this.index = 0;
    }

    /** Current position, exposed for diagnostics and tests. */
    public int index() {
        return this.index;
    }
}
