package com.dyllan.minekov.entities.ai.goals;

import com.dyllan.minekov.entities.AIOperator;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumSet;

public class DumbGunAttackGoal extends Goal {
    private final AIOperator mob;
    private LivingEntity target;
    private final double range;

    public DumbGunAttackGoal(AIOperator mob) {
        this.mob = mob;
        this.range = AIOperator.MAX_DISTANCE;
        this.setFlags(EnumSet.of(Goal.Flag.TARGET, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        this.target = TargetAcquisition.findTarget(mob);
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
        System.err.println("[GunAttackGoal] Target acquired: " + target);
    }

    @Override
    public void stop() {
        System.err.println("[GunAttackGoal] Lost sight of target");
        this.target = null;
    }

    @Override
    public void tick() {
        if (target == null) {
            mob.aim(false); // no target, no aiming necessary
            return;
        }

        // if there's a target, aim down sights
        mob.aim(true);
        this.mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        tryFireGun();
    }

    private void tryFireGun() {
        if (target == null) return;

        System.err.println("[GunAttackGoal] Attempting to fire");

        double dx = target.getX() - mob.getX();
        double dy = target.getEyeY() - mob.getEyeY();
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
