package com.dyllan.minekov.entities.ai.goals;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;

import java.util.EnumSet;
import java.util.List;

import com.dyllan.minekov.entities.AIOperator;

public class WatchClosestTargetGoal extends Goal {
    private final Mob mob;
    private final double range;
    private AIOperator target;
    private Vec3 currentDirection = Vec3.ZERO; // <-- store direction

    public WatchClosestTargetGoal(Mob mob, double range) {
        this.mob = mob;
        this.range = range;
        this.setFlags(EnumSet.of(Flag.LOOK, Flag.MOVE));
    }

    public Vec3 getCurrentDirection() {
        return currentDirection;
    }

    public LivingEntity getTarget() {
        return target;
    }

    @Override
    public boolean canUse() {
        List<AIOperator> operators = mob.level().getEntitiesOfClass(AIOperator.class, new AABB(
                mob.getX() - range, mob.getY() - range, mob.getZ() - range,
                mob.getX() + range, mob.getY() + range, mob.getZ() + range
        ));

        double closestDist = Double.MAX_VALUE;
        AIOperator closestTarget = null;

        for (AIOperator operator : operators) {
            // make sure we don't target ourselves
            if (operator == mob) continue;
            if (!operator.isAlive()) continue;
            // if (!mob.hasLineOfSight(operator)) continue;

            double dist = mob.distanceToSqr(operator);
            if (dist < closestDist) {
                closestDist = dist;
                closestTarget = operator;
            }
        }

        this.target = closestTarget;
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        return target != null && target.isAlive()// && mob.hasLineOfSight(target)
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
        currentDirection = Vec3.ZERO; // clear direction
    }

    @Override
    public void tick() {
        if (target == null) return;

        // Compute direction vector from mob eyes to target eyes
        double dx = target.getX() - mob.getX();
        double dy = target.getEyeY() - mob.getEyeY();
        double dz = target.getZ() - mob.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float pitch = (float) -Math.toDegrees(Math.atan2(dy, distXZ));
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;

        // Update mob look
        mob.setXRot(pitch);
        mob.setYRot(yaw);
        mob.yBodyRot = yaw;
        mob.yHeadRot = yaw;

        // Compute and store normalized horizontal direction vector
        Vec3 dir = new Vec3(dx, 0, dz);
        if (dir.lengthSqr() > 1e-6) {
            currentDirection = dir.normalize();
        } else {
            currentDirection = Vec3.ZERO;
        }

        // Optional: still call setLookAt so the AI "focuses" properly
        mob.getLookControl().setLookAt(target, 30.0F, 30.0F);
    }
}
