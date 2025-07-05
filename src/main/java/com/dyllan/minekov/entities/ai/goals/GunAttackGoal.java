package com.dyllan.minekov.entities.ai.goals;

import com.dyllan.minekov.entities.AIOperator;
import com.tacz.guns.api.entity.IGunOperator;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.EnumSet;
import java.util.List;

public class GunAttackGoal extends Goal {
    private final AIOperator mob;
    private LivingEntity target;
    private int cooldown = 0;
    private final double range;

    public GunAttackGoal(AIOperator mob) {
        this.mob = mob;
        this.range = 64.0D;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        this.target = findVisiblePlayer();
        return this.target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return this.target != null && this.target.isAlive() &&
               this.mob.hasLineOfSight(target) &&
               this.mob.distanceToSqr(target) < range * range;
    }

    @Override
    public void start() {
        System.err.println("[GunAttackGoal] Target acquired");
    }

    @Override
    public void stop() {
        System.err.println("[GunAttackGoal] Lost sight of target");
        this.target = null;
    }

    @Override
    public void tick() {
        if (target == null) return;

        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        if (--cooldown <= 0) {
            tryFireGun();
            cooldown = 3 + mob.getRandom().nextInt(5); // tap fire every ~1-1.5s
        }
    }

    private LivingEntity findVisiblePlayer() {
        List<Player> players = mob.level().getEntitiesOfClass(Player.class, new AABB(
                mob.getX() - range, mob.getY() - range, mob.getZ() - range,
                mob.getX() + range, mob.getY() + range, mob.getZ() + range
        ));

        double closestDist = Double.MAX_VALUE;
        Player closest = null;

        for (Player p : players) {
            if (!p.isAlive() || !mob.hasLineOfSight(p)) continue;

            double dist = mob.distanceToSqr(p);
            if (dist < closestDist) {
                closest = p;
                closestDist = dist;
            }
        }

        return closest;
    }

    private void tryFireGun() {
        if (target == null) return;

        System.err.println("[GunAttackGoal] Attempting to fire");

        // Calculate pitch and yaw from mob to target
        double dx = target.getX() - mob.getX();
        double dy = (target.getEyeY()) - (mob.getEyeY());
        double dz = target.getZ() - mob.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);
    
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
    
        try {
            mob.shoot(() -> pitch, () -> yaw);
        } catch (Exception e) {
            System.err.println("[GunAttackGoal] shoot() failed: " + e.getMessage());
        }
    }
    
}
