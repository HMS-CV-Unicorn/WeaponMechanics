package me.deecaad.weaponmechanics.weapon.projectile.predictive;

import me.deecaad.core.compatibility.HitBox;
import me.deecaad.core.utils.NumberUtil;
import me.deecaad.core.utils.ray.RayTrace;
import me.deecaad.core.utils.ray.RayTraceResult;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Drop-in replacement for {@link RayTrace} that uses a {@link HitboxHistoryManager} to roll back
 * player hitboxes to where they were at the moment a shot was fired. This is what gives predictive
 * mode its CS-style "what your crosshair saw is what hits" feel.
 *
 * <p>Block raytracing is delegated to a private {@link RayTrace} instance configured for
 * blocks-only. Entity raytracing is performed in-house so we can substitute a rewound bounding box
 * per target. Non-player living entities (mobs, NPCs) are evaluated with their current bounding box
 * — they have no client-side latency to compensate for.
 */
public class LagCompensatedRayTrace extends RayTrace {

    private final @NotNull HitboxHistoryManager historyManager;
    private final int rollbackTicks;
    private final boolean entityChecksDisabled;
    private final @Nullable Predicate<LivingEntity> entityFilter;
    private final double raySize;

    private final @NotNull RayTrace blockDelegate;

    public LagCompensatedRayTrace(
        @NotNull HitboxHistoryManager historyManager,
        int rollbackTicks,
        @Nullable Predicate<Block> blockFilter,
        @Nullable Predicate<LivingEntity> entityFilter,
        boolean entityChecksDisabled,
        boolean allowLiquid,
        double raySize) {
        this.historyManager = historyManager;
        this.rollbackTicks = rollbackTicks;
        this.entityChecksDisabled = entityChecksDisabled;
        this.entityFilter = entityFilter;
        this.raySize = raySize;

        RayTrace delegate = new RayTrace()
            .disableEntityChecks()
            .withRaySize(raySize);
        if (blockFilter != null)
            delegate.withBlockFilter(blockFilter);
        if (allowLiquid)
            delegate.enableLiquidChecks();
        this.blockDelegate = delegate;
    }

    @Override
    public List<RayTraceResult> cast(World world, Vector start, Vector end, Vector direction, double maximumBlockThrough) {
        List<RayTraceResult> merged = new ArrayList<>(4);

        List<RayTraceResult> blockHits = blockDelegate.cast(world, start, end, direction, maximumBlockThrough);
        if (blockHits != null)
            merged.addAll(blockHits);

        if (!entityChecksDisabled)
            collectEntityHits(merged, world, start, end, direction);

        if (merged.isEmpty())
            return null;

        if (merged.size() > 1)
            merged.sort(Comparator.comparingDouble(RayTraceResult::getHitMin));
        return merged;
    }

    @Override
    public List<RayTraceResult> cast(World world, Vector start, Vector end, Vector direction) {
        return cast(world, start, end, direction, 0.0);
    }

    @Override
    public List<RayTraceResult> cast(World world, Vector start, Vector end) {
        return cast(world, start, end, end.clone().subtract(start).normalize(), 0.0);
    }

    @Override
    public List<RayTraceResult> cast(World world, Vector start, Vector direction, double range) {
        Vector end = start.clone().add(direction.clone().multiply(range));
        return cast(world, start, end, direction, 0.0);
    }

    private void collectEntityHits(List<RayTraceResult> hits, World world, Vector start, Vector end, Vector direction) {
        // Mirrors RayTrace.getEntityHits chunk-window logic so we visit the same entities the
        // stock raytrace would.
        HitBox rayBounds = new HitBox(start, end);

        int minX = NumberUtil.floorToInt((rayBounds.getMinX() - 2.0) / 16.0);
        int maxX = NumberUtil.floorToInt((rayBounds.getMaxX() + 2.0) / 16.0);
        int minZ = NumberUtil.floorToInt((rayBounds.getMinZ() - 2.0) / 16.0);
        int maxZ = NumberUtil.floorToInt((rayBounds.getMaxZ() + 2.0) / 16.0);

        for (int x = minX; x <= maxX; ++x) {
            for (int z = minZ; z <= maxZ; ++z) {
                Chunk chunk = world.getChunkAt(x, z);
                for (Entity entity : chunk.getEntities()) {
                    RayTraceResult hit = rayEntity(rayBounds, entity, start, direction);
                    if (hit != null)
                        hits.add(hit);
                }
            }
        }
    }

    private @Nullable RayTraceResult rayEntity(HitBox rayBounds, Entity entity, Vector start, Vector direction) {
        if (!entity.getType().isAlive())
            return null;
        if (!(entity instanceof LivingEntity living))
            return null;
        if (living.isInvulnerable() || living.isDead())
            return null;
        if (entityFilter != null && entityFilter.test(living))
            return null;

        BoundingBox sourceBox;
        if (entity instanceof Player) {
            sourceBox = historyManager.getRewoundBoundingBox(living, rollbackTicks);
        } else {
            sourceBox = entity.getBoundingBox();
        }
        if (sourceBox == null)
            return null;

        HitBox entityBox = HitBox.getHitbox(sourceBox);
        entityBox.setLivingEntity(living);
        entityBox.grow(raySize);

        if (!rayBounds.overlaps(entityBox))
            return null;

        return entityBox.rayTrace(start, direction);
    }
}
