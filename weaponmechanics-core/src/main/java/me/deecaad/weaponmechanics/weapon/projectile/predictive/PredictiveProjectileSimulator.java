package me.deecaad.weaponmechanics.weapon.projectile.predictive;

import me.deecaad.core.utils.ray.RayTrace;
import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.weapon.projectile.weaponprojectile.ProjectileSettings;
import me.deecaad.weaponmechanics.weapon.projectile.weaponprojectile.WeaponProjectile;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Runs the full trajectory of a {@link WeaponProjectile} synchronously at fire time. Every hit
 * the projectile would experience over its entire lifetime is determined right now, against
 * hitboxes rewound by the shooter's effective display latency. The hits are returned as a
 * {@link SimulationResult} so the caller can schedule damage application at the moment the bullet
 * would actually arrive.
 *
 * <p>This is what makes the gun feel like a real FPS while still preserving WeaponMechanics
 * features (Through / Bouncy / Sticky / Max travel distance / drag / gravity) — the same
 * per-tick physics still runs, just compressed into a single synchronous loop and against
 * lag-compensated state.
 */
public class PredictiveProjectileSimulator {

    /** Default rollback used when the shooter has no measurable ping. ~100ms client interpolation. */
    private static final int DEFAULT_INTERP_TICKS = 2;
    private static final int MIN_ROLLBACK_TICKS = 0;
    private static final int MAX_ROLLBACK_TICKS = 10;

    private final @NotNull HitboxHistoryManager historyManager;

    public PredictiveProjectileSimulator(@NotNull HitboxHistoryManager historyManager) {
        this.historyManager = historyManager;
    }

    /**
     * Compute how many ticks of history to rewind for this shooter. Combines the player's ping
     * with a fixed client interpolation budget. Returns {@link #DEFAULT_INTERP_TICKS} for
     * non-players and capped at {@link #MAX_ROLLBACK_TICKS} so we never reach back past the
     * available history window.
     */
    public int computeRollbackTicks(@NotNull WeaponProjectile projectile) {
        LivingEntity shooter = projectile.getShooter();
        if (!(shooter instanceof Player player))
            return DEFAULT_INTERP_TICKS;

        int pingMs;
        try {
            pingMs = Math.max(0, player.getPing());
        } catch (Throwable t) {
            pingMs = 0;
        }
        int pingTicks = pingMs / 50;
        int total = pingTicks + DEFAULT_INTERP_TICKS;
        return Math.max(MIN_ROLLBACK_TICKS, Math.min(MAX_ROLLBACK_TICKS, total));
    }

    /**
     * Simulate the bullet's trajectory in one go. Returns a list of hits (entity + block) with
     * their tick offsets from fire time, plus a list of path samples that the disguise animator
     * can use to render the bullet's flight.
     *
     * <p>While simulating, Through / Bouncy / Sticky logic on the projectile is still invoked
     * exactly as in physical mode — these decisions depend only on the projectile's own state.
     * Only {@link me.deecaad.weaponmechanics.weapon.HitHandler#handleHit} is intercepted, so the
     * damage application can be deferred until the bullet would actually arrive.
     */
    public @NotNull SimulationResult simulate(@NotNull WeaponProjectile projectile) {
        ProjectileSettings settings = projectile.getProjectileSettings();
        int rollbackTicks = settings.isLagCompensation() ? computeRollbackTicks(projectile) : 0;

        installLagCompensatedRayTrace(projectile, settings, rollbackTicks);

        List<DeferredHit> deferred = new ArrayList<>(2);
        List<PathSample> path = new ArrayList<>(32);

        int[] virtualTickHolder = {0};
        projectile.setHitInterceptor((hit, proj) -> {
            deferred.add(new DeferredHit(hit, virtualTickHolder[0] + 1));
            return false;
        });

        path.add(new PathSample(projectile.getLocation(), 0));

        int maxAliveTicks = settings.getMaximumAliveTicks();
        Vector lastLocation = projectile.getLocation();

        while (!projectile.isDead() && virtualTickHolder[0] < maxAliveTicks) {
            boolean dead;
            try {
                dead = projectile.tick();
            } catch (Throwable t) {
                WeaponMechanics.getInstance().debugger.warning(
                    "An unhandled exception occurred while running predictive projectile simulation", t);
                break;
            }

            virtualTickHolder[0] = projectile.getAliveTicks();
            Vector here = projectile.getLocation();
            path.add(new PathSample(here, virtualTickHolder[0]));

            if (dead)
                break;

            // Guard against stalled bullets: zero motion with zero gravity means we'd loop forever.
            if (projectile.getGravity() == 0.0 && projectile.getMotionLength() < Vector.getEpsilon()
                && here.distanceSquared(lastLocation) < 1.0e-6) {
                break;
            }
            lastLocation = here;
        }

        // Clear the interceptor so any further use of the projectile object behaves normally.
        projectile.setHitInterceptor(null);

        return new SimulationResult(deferred, path, virtualTickHolder[0]);
    }

    /**
     * Reconfigure the projectile's ray trace to consult lag-compensated player hitboxes. The
     * filters mirror the originals from {@link WeaponProjectile}'s constructor so block-filter
     * and entity-filter behavior is preserved.
     */
    private void installLagCompensatedRayTrace(WeaponProjectile projectile, ProjectileSettings settings, int rollbackTicks) {
        RayTrace existing = projectile.getRayTrace();

        Predicate<Block> blockFilter = projectile::isRecentlyHitBlock;
        Predicate<LivingEntity> entityFilter = entity ->
            projectile.isRecentlyHitEntity(entity)
                || (projectile.getShooter() != null
                    && projectile.getAliveTicks() < 10
                    && entity.getEntityId() == projectile.getShooter().getEntityId())
                || entity.getPassengers().contains(projectile.getShooter());

        LagCompensatedRayTrace replacement = new LagCompensatedRayTrace(
            historyManager,
            rollbackTicks,
            blockFilter,
            entityFilter,
            settings.isDisableEntityCollisions(),
            true,
            settings.getSize());
        projectile.setRayTrace(replacement);

        // existing was discarded; the local reference satisfies analyzers
        if (existing == null) {
            throw new IllegalStateException("Projectile must already have a ray trace before predictive simulation");
        }
    }

    /**
     * One hit captured during simulation, paired with the virtual tick (relative to fire time) it
     * occurred on. The {@code RayTraceResult} carries the entity/block and hit location.
     */
    public record DeferredHit(@NotNull me.deecaad.core.utils.ray.RayTraceResult hit, int tickOffset) {
    }

    /** A point along the precomputed path, used to animate the bullet's visual disguise. */
    public record PathSample(@NotNull Vector location, int tickOffset) {
    }

    /**
     * Aggregate of everything produced by a simulation run.
     */
    public record SimulationResult(@NotNull List<DeferredHit> hits, @NotNull List<PathSample> path, int aliveTicks) {
    }
}
