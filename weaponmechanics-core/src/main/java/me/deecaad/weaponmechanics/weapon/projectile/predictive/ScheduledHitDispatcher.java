package me.deecaad.weaponmechanics.weapon.projectile.predictive;

import com.cjcrafter.foliascheduler.ServerImplementation;
import me.deecaad.core.compatibility.CompatibilityAPI;
import me.deecaad.core.compatibility.entity.FakeEntity;
import me.deecaad.core.utils.ray.RayTraceResult;
import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.weapon.projectile.weaponprojectile.ProjectileSettings;
import me.deecaad.weaponmechanics.weapon.projectile.weaponprojectile.WeaponProjectile;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Dispatches the outputs of a {@link PredictiveProjectileSimulator} run:
 * <ul>
 *   <li>Spawns a {@link FakeEntity} disguise and animates it along the precomputed path so the
 *       visual matches the kinematic trajectory the user expects from {@code Projectile_Speed}.</li>
 *   <li>Schedules each deferred hit to be applied at the tick the bullet would actually reach the
 *       hit point. Hits go through the normal {@link me.deecaad.weaponmechanics.weapon.HitHandler}
 *       so all existing damage/explosion/event behaviors fire as usual.</li>
 *   <li>Fires {@link ProjectileEndEvent} after the final scheduled hit (or, if no hits, after the
 *       bullet's full flight time) so cosmetic plugins still get the projectile-end callback.</li>
 * </ul>
 */
public class ScheduledHitDispatcher {

    private ScheduledHitDispatcher() {}

    public static void dispatch(@NotNull WeaponProjectile projectile,
                                @NotNull PredictiveProjectileSimulator.SimulationResult result,
                                @NotNull Location originLocation) {
        ServerImplementation scheduler = WeaponMechanics.getInstance().getFoliaScheduler();
        ProjectileSettings settings = projectile.getProjectileSettings();

        // Attach scripts now that the synchronous burst is done. Reset the alive-tick counter so
        // scripts see a fresh-from-fire-time projectile during animation, even though the
        // simulator already advanced it internally.
        WeaponMechanics.getInstance().getProjectileSpawner().attachScripts(projectile);
        projectile.setAliveTicks(0);
        try {
            projectile.onStart();
        } catch (Throwable ex) {
            WeaponMechanics.getInstance().debugger.warning(
                "An unhandled exception occurred while firing predictive projectile onStart", ex);
        }

        FakeEntity disguise = spawnDisguise(settings, originLocation);
        animatePath(projectile, disguise, result, scheduler, originLocation);

        scheduleHits(projectile, result.hits(), scheduler);
        scheduleEndEvent(projectile, result, scheduler, disguise);
    }

    private static @Nullable FakeEntity spawnDisguise(ProjectileSettings settings, Location originLocation) {
        EntityType type = settings.getProjectileDisguise();
        if (type == null)
            return null;

        Object data = settings.getDisguiseData();
        Location spawnLoc = originLocation.clone();
        FakeEntity fakeEntity;

        if (type == EntityType.ARMOR_STAND && data != null) {
            Location offset = new Location(spawnLoc.getWorld(), 0, -1.67875, 0);
            spawnLoc.add(offset);
            fakeEntity = CompatibilityAPI.getEntityCompatibility().generateFakeEntity(spawnLoc, type, data);
            fakeEntity.setEquipment(EquipmentSlot.HEAD, (ItemStack) data);
            fakeEntity.setInvisible(true);
            fakeEntity.setOffset(offset);
        } else {
            fakeEntity = CompatibilityAPI.getEntityCompatibility().generateFakeEntity(spawnLoc, type, data);
        }

        if (settings.getGravity() == 0.0)
            fakeEntity.setGravity(false);

        if (settings.isIncendiaryProjectile()) {
            fakeEntity.setOnFire(true);
        }

        fakeEntity.show();
        return fakeEntity;
    }

    /**
     * Drives the projectile through its precomputed path at real-tick cadence so attached
     * {@link me.deecaad.weaponmechanics.weapon.projectile.ProjectileScript} hooks (cosmetic
     * trails, WMP integrations) fire spaced over real time rather than in the synchronous
     * simulation burst. The visual disguise is moved alongside if present.
     *
     * <p>Each tick the projectile's location, motion, and {@code aliveTicks} are stepped forward
     * to the matching path sample before {@code onTickStart} is fired, so scripts that read those
     * fields see consistent state. Any deferred hit whose tickOffset matches the current step
     * also fires the {@code onCollide} script hook (damage application is scheduled separately).
     */
    private static void animatePath(WeaponProjectile projectile, @Nullable FakeEntity disguise,
                                    PredictiveProjectileSimulator.SimulationResult result,
                                    ServerImplementation scheduler, Location originLocation) {
        List<PredictiveProjectileSimulator.PathSample> path = result.path();
        List<PredictiveProjectileSimulator.DeferredHit> hits = result.hits();

        if (path.size() < 2) {
            if (disguise != null)
                scheduler.region(originLocation).runDelayed(() -> disguise.remove(), 2L);
            return;
        }

        int[] index = {1};
        scheduler.region(originLocation).runAtFixedRate(task -> {
            if (index[0] >= path.size()) {
                if (disguise != null)
                    disguise.remove();
                task.cancel();
                return;
            }
            PredictiveProjectileSimulator.PathSample current = path.get(index[0]);
            PredictiveProjectileSimulator.PathSample previous = path.get(index[0] - 1);
            Vector delta = current.location().clone().subtract(previous.location());

            // Sync the projectile's per-tick state so scripts reading getLocation/getMotion/
            // getAliveTicks during their callbacks see what they would see in PHYSICAL mode.
            projectile.setRawLocation(current.location());
            projectile.setMotion(delta);
            projectile.setAliveTicks(current.tickOffset());

            try {
                projectile.fireScriptTickStart();
            } catch (Throwable ex) {
                WeaponMechanics.getInstance().debugger.warning(
                    "An unhandled exception occurred during predictive onTickStart", ex);
            }

            if (disguise != null) {
                float yaw = computeYaw(delta);
                float pitch = computePitch(delta);
                disguise.setPosition(current.location().getX(), current.location().getY(), current.location().getZ(), yaw, pitch, false);
                disguise.setMotion(delta);
            }

            // Fire onCollide for any hit whose tickOffset matches this animation step. This is
            // independent of damage application — damage is scheduled separately in scheduleHits.
            for (PredictiveProjectileSimulator.DeferredHit deferred : hits) {
                if (deferred.tickOffset() == current.tickOffset()) {
                    try {
                        projectile.onCollide(deferred.hit());
                    } catch (Throwable ex) {
                        WeaponMechanics.getInstance().debugger.warning(
                            "An unhandled exception occurred during predictive onCollide", ex);
                    }
                }
            }

            try {
                projectile.fireScriptTickEnd();
            } catch (Throwable ex) {
                WeaponMechanics.getInstance().debugger.warning(
                    "An unhandled exception occurred during predictive onTickEnd", ex);
            }

            index[0]++;
        }, 1L, 1L);
    }

    private static void scheduleHits(WeaponProjectile projectile, List<PredictiveProjectileSimulator.DeferredHit> hits,
                                     ServerImplementation scheduler) {
        for (PredictiveProjectileSimulator.DeferredHit deferred : hits) {
            int delayTicks = Math.max(1, deferred.tickOffset());
            RayTraceResult hit = deferred.hit();
            Location at = hit.getHitLocation().toLocation(projectile.getWorld());
            scheduler.region(at).runDelayed(() -> {
                try {
                    WeaponMechanics.getInstance()
                        .getWeaponHandler()
                        .getHitHandler()
                        .handleHit(hit, projectile);
                } catch (Throwable ex) {
                    WeaponMechanics.getInstance().debugger.warning(
                        "An unhandled exception occurred while applying a predictive hit", ex);
                }
            }, delayTicks);
        }
    }

    private static void scheduleEndEvent(WeaponProjectile projectile, PredictiveProjectileSimulator.SimulationResult result,
                                          ServerImplementation scheduler, FakeEntity disguise) {
        int endTick = Math.max(1, result.aliveTicks());
        Location endLoc = projectile.getBukkitLocation();
        scheduler.region(endLoc).runDelayed(() -> {
                try {
                    // remove() fires onEnd() which fires ProjectileEndEvent and the script onEnd hook.
                    projectile.remove();
                } catch (Throwable ex) {
                    WeaponMechanics.getInstance().debugger.warning(
                        "An unhandled exception occurred while ending a predictive projectile", ex);
                }
            }, endTick);
    }

    private static float computeYaw(Vector delta) {
        double len = Math.sqrt(delta.getX() * delta.getX() + delta.getZ() * delta.getZ());
        if (len < 1.0e-6)
            return 0f;
        double piTwo = Math.PI * 2.0;
        return (float) Math.toDegrees((Math.atan2(-delta.getX(), delta.getZ()) + piTwo) % piTwo);
    }

    private static float computePitch(Vector delta) {
        double horiz = Math.sqrt(NumberConversions.square(delta.getX()) + NumberConversions.square(delta.getZ()));
        if (horiz < 1.0e-6)
            return delta.getY() > 0 ? -90f : 90f;
        return (float) Math.toDegrees(Math.atan(-delta.getY() / horiz));
    }
}
