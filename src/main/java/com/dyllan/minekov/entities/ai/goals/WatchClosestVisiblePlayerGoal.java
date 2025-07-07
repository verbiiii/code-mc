package com.dyllan.minekov.entities.ai.goals;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.EnumSet;
import java.util.List;

public class WatchClosestVisiblePlayerGoal extends Goal {
    private final Mob mob;
    private final double range;
    private Player target;

    public WatchClosestVisiblePlayerGoal(Mob mob, double range) {
        this.mob = mob;
        this.range = range;
        this.setFlags(EnumSet.of(Flag.LOOK, Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        List<Player> players = mob.level().getEntitiesOfClass(Player.class, new AABB(
                mob.getX() - range, mob.getY() - range, mob.getZ() - range,
                mob.getX() + range, mob.getY() + range, mob.getZ() + range
        ));

        double closestDist = Double.MAX_VALUE;
        Player closestVisible = null;

        for (Player player : players) {
            if (!player.isAlive()) continue;
            if (!mob.hasLineOfSight(player)) continue;

            double dist = mob.distanceToSqr(player);
            if (dist < closestDist) {
                closestDist = dist;
                closestVisible = player;
            }
        }

        this.target = closestVisible;
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && mob.hasLineOfSight(target)
                && mob.distanceToSqr(target) < range * range;
    }

    @Override
    public void start() {
        // mob.setSprinting(true);
    }

    @Override
    public void stop() {
        target = null;
        mob.setDeltaMovement(Vec3.ZERO);
    }

    @Override
    public void tick() {
        if (target == null) return;

        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSq = mob.distanceToSqr(target);
        if (distSq < 2 * 2) {
            // Close enough — stop moving
            mob.setDeltaMovement(Vec3.ZERO);
            return;
        }

        double dx = mob.getLookControl().getWantedX() - mob.getX();
        double dz = mob.getLookControl().getWantedZ() - mob.getZ();

        Vec3 dir = new Vec3(dx, 0, dz);
        if (dir.lengthSqr() > 1e-6) {
            dir = dir.normalize();
        } else {
            dir = Vec3.ZERO;
        }

        double speed = 0.13; // Match player sprint
        Vec3 velocity = new Vec3(dir.x * speed, mob.getDeltaMovement().y, dir.z * speed);
        mob.setDeltaMovement(velocity);

        // Optional: rotate body to face movement
        float yaw = (float)(Mth.atan2(-dir.x, dir.z) * (180F / Math.PI));
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;
    }
}
