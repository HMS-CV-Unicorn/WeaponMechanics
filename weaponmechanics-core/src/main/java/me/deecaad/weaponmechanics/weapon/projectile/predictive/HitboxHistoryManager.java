package me.deecaad.weaponmechanics.weapon.projectile.predictive;

import com.cjcrafter.foliascheduler.ServerImplementation;
import com.cjcrafter.foliascheduler.TaskImplementation;
import me.deecaad.weaponmechanics.WeaponMechanics;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures a recent history of every online player's bounding box so that predictive bullet
 * simulation can rewind hitbox positions by the shooter's effective display latency.
 *
 * <p>Only players are tracked. Non-player living entities are evaluated against their current
 * bounding box at simulation time (no rewind needed — they have no ping).
 *
 * <p>Recording is driven by a per-player, entity-scoped scheduler task so it stays Folia-safe.
 */
public class HitboxHistoryManager implements Listener {

    private final ConcurrentHashMap<UUID, HitboxHistory> histories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, TaskImplementation<Void>> tasks = new ConcurrentHashMap<>();

    /**
     * Starts recording for any players already online. Call this once after the manager is
     * registered as a listener.
     */
    public void initializeOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            schedule(player);
        }
    }

    /**
     * Cancels every scheduled recording task and clears all stored history. Intended for plugin
     * disable.
     */
    public void shutdown() {
        for (TaskImplementation<Void> task : tasks.values()) {
            if (task != null)
                task.cancel();
        }
        tasks.clear();
        histories.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        schedule(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        TaskImplementation<Void> task = tasks.remove(uuid);
        if (task != null)
            task.cancel();
        histories.remove(uuid);
    }

    private void schedule(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (tasks.containsKey(uuid))
            return;

        ServerImplementation scheduler = WeaponMechanics.getInstance().getFoliaScheduler();
        TaskImplementation<Void> task = scheduler.entity(player).runAtFixedRate(t -> {
            if (!player.isOnline() || player.isDead()) {
                return;
            }
            HitboxHistory history = histories.computeIfAbsent(uuid, k -> new HitboxHistory());
            history.record(player.getBoundingBox(), currentTick());
        }, 1L, 1L);
        if (task != null)
            tasks.put(uuid, task);
    }

    /**
     * Resolves the {@link BoundingBox} of an entity at a point {@code rollbackTicks} ticks before
     * now. Players are rewound through {@link HitboxHistory}; non-player entities are returned
     * with their current bounding box.
     *
     * @return the rewound bounding box, or null if the entity has no history and no current box
     */
    public @Nullable BoundingBox getRewoundBoundingBox(@NotNull LivingEntity entity, int rollbackTicks) {
        if (entity instanceof Player) {
            HitboxHistory history = histories.get(entity.getUniqueId());
            if (history != null) {
                BoundingBox box = history.getAt(currentTick() - rollbackTicks);
                if (box != null)
                    return box;
            }
            // Fall through to current bounding box if no snapshot exists yet
        }
        return entity.getBoundingBox();
    }

    /**
     * @return a monotonic tick counter expressed in 50ms units since the unix epoch. Stable across
     *     Folia region threads.
     */
    public static long currentTick() {
        return System.currentTimeMillis() / 50L;
    }
}
