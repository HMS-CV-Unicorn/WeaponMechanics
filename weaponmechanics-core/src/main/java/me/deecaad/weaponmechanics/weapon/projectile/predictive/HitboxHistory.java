package me.deecaad.weaponmechanics.weapon.projectile.predictive;

import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Ring buffer of recent {@link BoundingBox} snapshots for a single entity. Used to rewind hitbox
 * positions when running lag-compensated hit detection: at the moment a shooter fires, the target's
 * hitbox is rolled back to where it was the same number of milliseconds ago as the shooter's
 * apparent display latency (ping + client interpolation).
 *
 * <p>Snapshots are stored alongside the absolute server tick they were captured on. Lookups choose
 * the snapshot whose tick is closest to the requested tick. With a snapshot taken every server tick
 * (50ms), the worst-case rounding error is ±25ms — far smaller than a player hitbox at sprint
 * speed.
 *
 * <p>Not thread safe. Each instance should only be touched on the region thread that owns its
 * entity.
 */
public class HitboxHistory {

    /** Capacity in snapshots. 10 entries × 50ms/tick = 500ms of rollback. */
    public static final int DEFAULT_CAPACITY = 10;

    private final BoundingBox[] boxes;
    private final long[] ticks;
    private final int capacity;

    /** Number of valid entries written so far, capped at {@link #capacity}. */
    private int size;
    /** Index where the next snapshot will be written. */
    private int head;

    public HitboxHistory() {
        this(DEFAULT_CAPACITY);
    }

    public HitboxHistory(int capacity) {
        if (capacity <= 0)
            throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.boxes = new BoundingBox[capacity];
        this.ticks = new long[capacity];
    }

    /**
     * Append a snapshot of the entity's bounding box at the given server tick. The bounding box is
     * cloned defensively — callers can reuse the original object.
     */
    public void record(@NotNull BoundingBox box, long tick) {
        boxes[head] = box.clone();
        ticks[head] = tick;
        head = (head + 1) % capacity;
        if (size < capacity)
            size++;
    }

    /**
     * Returns the bounding box recorded closest in time to the requested tick. If the requested
     * tick is older than anything we still hold, the oldest available snapshot is returned. If
     * newer, the most recent snapshot. Returns null only when nothing has been recorded yet.
     */
    public @Nullable BoundingBox getAt(long targetTick) {
        if (size == 0)
            return null;

        int bestIndex = -1;
        long bestDelta = Long.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            long delta = Math.abs(ticks[i] - targetTick);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestIndex = i;
            }
        }
        return bestIndex == -1 ? null : boxes[bestIndex];
    }

    /**
     * @return the latest recorded snapshot, or null if empty
     */
    public @Nullable BoundingBox getLatest() {
        if (size == 0)
            return null;
        int lastIndex = (head - 1 + capacity) % capacity;
        return boxes[lastIndex];
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return capacity;
    }
}
