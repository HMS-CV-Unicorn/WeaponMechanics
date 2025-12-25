package me.deecaad.weaponmechanics.weapon.weaponevents;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Called before checking weapon delays (equip delay and scope delay).
 * Allows plugins to modify delay values based on attachments or other factors.
 */
public class WeaponDelayModifyEvent extends WeaponEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private int equipDelay;
    private int scopeDelay;

    public WeaponDelayModifyEvent(String weaponTitle, ItemStack weaponStack, LivingEntity shooter,
            EquipmentSlot hand, int equipDelay, int scopeDelay) {
        super(weaponTitle, weaponStack, shooter, hand);
        this.equipDelay = equipDelay;
        this.scopeDelay = scopeDelay;
    }

    /**
     * Gets the current weapon equip delay in milliseconds.
     * 
     * @return The equip delay
     */
    public int getEquipDelay() {
        return equipDelay;
    }

    /**
     * Sets the weapon equip delay in milliseconds.
     * 
     * @param equipDelay The new equip delay
     */
    public void setEquipDelay(int equipDelay) {
        this.equipDelay = equipDelay;
    }

    /**
     * Gets the current shoot delay after scope in milliseconds.
     * 
     * @return The scope delay
     */
    public int getScopeDelay() {
        return scopeDelay;
    }

    /**
     * Sets the shoot delay after scope in milliseconds.
     * 
     * @param scopeDelay The new scope delay
     */
    public void setScopeDelay(int scopeDelay) {
        this.scopeDelay = scopeDelay;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
