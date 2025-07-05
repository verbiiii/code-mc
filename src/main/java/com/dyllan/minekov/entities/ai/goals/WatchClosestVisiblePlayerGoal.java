package com.dyllan.minekov.entities.ai.goals;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.Mob;

import java.util.EnumSet;
import java.util.List;

public class WatchClosestVisiblePlayerGoal extends Goal {
    private final Mob mob;
    private final double range;
    private Player target;

    public WatchClosestVisiblePlayerGoal(Mob mob, double range) {
        this.mob = mob;
        this.range = range;
        this.setFlags(EnumSet.of(Goal.Flag.LOOK));
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
    public void start() {
        if (target != null) {
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive() && mob.hasLineOfSight(target) &&
               mob.distanceToSqr(target) < range * range;
    }

    @Override
    public void tick() {
        if (target != null) {
            mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
        }
    }
}
