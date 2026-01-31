package me.deecaad.weaponmechanics.weapon.weaponevents;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * This class outlines the event of a player holding a weapon. This can be done
 * by picking up the
 * item, switching the hot bar slots, etc.
 * 
 * The equipDelay and scopeDelay fields contain the MODIFIED delay values after
 * WeaponDelayModifyEvent has been applied. Cosmetics plugins should use these
 * values for accurate indicator display.
 */
public class WeaponEquipEvent extends WeaponEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private int equipDelay;
    private int scopeDelay;

    public WeaponEquipEvent(String weaponTitle, ItemStack weaponStack, LivingEntity shooter, boolean mainHand) {
        super(weaponTitle, weaponStack, shooter, mainHand ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND);
        this.equipDelay = 0;
        this.scopeDelay = 0;
    }

    public WeaponEquipEvent(String weaponTitle, ItemStack weaponStack, LivingEntity shooter, boolean mainHand,
            int equipDelay, int scopeDelay) {
        super(weaponTitle, weaponStack, shooter, mainHand ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND);
        this.equipDelay = equipDelay;
        this.scopeDelay = scopeDelay;
    }

    /**
     * Gets the modified equip delay in milliseconds.
     * This value has been adjusted by WeaponDelayModifyEvent.
     * 
     * @return The equip delay in milliseconds
     */
    public int getEquipDelay() {
        return equipDelay;
    }

    /**
     * Gets the modified scope delay in milliseconds.
     * This value has been adjusted by WeaponDelayModifyEvent.
     * 
     * @return The scope delay in milliseconds
     */
    public int getScopeDelay() {
        return scopeDelay;
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