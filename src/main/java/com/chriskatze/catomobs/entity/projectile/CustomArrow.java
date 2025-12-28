package com.chriskatze.catomobs.entity.projectile;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class CustomArrow extends AbstractArrow {

    // Constructor
    public CustomArrow(Level level, LivingEntity owner) {
        super(EntityType.ARROW, level);
        this.setOwner(owner); // Set the owner (shooter)
    }

    // This method is called when the arrow hits an entity
    @Override
    public void onHit(HitResult hitResult) {
        super.onHit(hitResult);  // Call the super method for default behavior

        // Check if the hit result is an EntityHitResult (i.e., it hit an entity)
        if (hitResult instanceof EntityHitResult entityHitResult) {
            // Get the entity we hit
            Entity target = entityHitResult.getEntity();

            // Ensure it's a living entity and not null
            if (target instanceof LivingEntity livingTarget) {
                LivingEntity owner = (LivingEntity) this.getOwner();  // Ensure the owner is cast to LivingEntity
                if (owner != null) {
                    // Apply damage to the target
                    livingTarget.hurt(this.damageSources().mobAttack(owner), (float) this.getBaseDamage());
                }
            }
        }
    }

    // Override this method to define the pickup item.
    // This is for when the projectile can be picked up, e.g., by the player.
    @Override
    public ItemStack getDefaultPickupItem() {
        return new ItemStack(Items.ARROW);  // You can later customize this to another item if needed
    }

    /**
     * Used for shooting the arrow from the entity
     * @param target The target entity (LivingEntity) to shoot the arrow at.
     * @param damage The damage to apply when the arrow hits the target.
     */
    public static void shootArrow(LivingEntity shooter, LivingEntity target, double damage) {
        if (target == null || !target.isAlive()) return;

        Level level = shooter.level();
        if (level.isClientSide) return;

        // Create the custom arrow
        CustomArrow arrow = new CustomArrow(level, shooter);

        // Make it behave like an actual attack projectile
        arrow.setOwner(shooter); // Set the shooter as the owner
        arrow.setBaseDamage(Math.max(0.0D, damage)); // Set the damage (use setBaseDamage instead of setDamage)

        // Aim at the target
        double dx = target.getX() - shooter.getX();
        double dy = target.getEyeY() - arrow.getY();
        double dz = target.getZ() - shooter.getZ();

        // Tune these later via species config if you want:
        float velocity = 1.6F;     // how fast it flies
        float inaccuracy = 0.0F;   // how “spread” it is

        arrow.shoot(dx, dy, dz, velocity, inaccuracy);  // Adjust projectile velocity and inaccuracy
        level.addFreshEntity(arrow);  // Add the arrow to the world
    }
}
