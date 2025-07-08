package com.dyllan.minekov.entities;

import com.dyllan.minekov.entities.ai.goals.WatchClosestTargetGoal;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public class RLOperator extends AIOperator {
    private WatchClosestTargetGoal watchGoal;

    public RLOperator(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        watchGoal = new WatchClosestTargetGoal(this, 64.0D);
        this.goalSelector.addGoal(1, watchGoal);
    }

    // @Override
    // public void tick() {
    //     super.tick();

    //     this.setSprinting(true);

    //     double dx = this.getLookControl().getWantedX() - this.getX();
    //     double dz = this.getLookControl().getWantedZ() - this.getZ();

    //     Vec3 dir = new Vec3(dx, 0, dz);
    //     if (dir.lengthSqr() > 1e-6) {
    //         dir = dir.normalize();
    //     } else {
    //         dir = Vec3.ZERO;
    //     }

    //     // Match vanilla player sprint speed (0.13 blocks/tick)
    //     double targetSpeed = 0.13;
    //     Vec3 targetVelocity = new Vec3(dir.x * targetSpeed, this.getDeltaMovement().y, dir.z * targetSpeed);
    //     this.setDeltaMovement(targetVelocity);
    // }

    /**
     * Moves the entity in the direction relative to the current goal's direction vector.
     *
     * @param thetaDeg Relative angle in degrees (0 = forward, ±90 = strafe, 180 = backward)
     * @param speed    Desired movement speed (e.g., 0.0 to movement speed attribute)
     */
    public void moveTowards(float thetaDeg, float speed) {
        Vec3 baseDir = this.watchGoal != null ? this.watchGoal.getCurrentDirection() : Vec3.ZERO;

        // if (baseDir.lengthSqr() < 1e-6) {
        //     return;
        // }

        // Normalize and flatten the base direction
        Vec3 forward = new Vec3(baseDir.x, 0, baseDir.z).normalize();
        Vec3 right = new Vec3(forward.z, 0, -forward.x); // 90° rotated vector for strafe

        double thetaRad = Math.toRadians(thetaDeg);

        // Combine forward + right based on relative angle
        Vec3 moveVec = forward.scale(Math.cos(thetaRad)).add(right.scale(Math.sin(thetaRad))).normalize().scale(speed);

        // Preserve Y velocity
        this.setDeltaMovement(moveVec.x, this.getDeltaMovement().y, moveVec.z);
    }

    @Override
    public LivingEntity getTarget() {
        if (watchGoal != null) {
            return this.watchGoal.getTarget();
        }
        return null;
    }

    @Override
    public void tick() {
        super.tick();

        // this.moveTowards(0, 0.13f); // always moving forward (holding W)
        // this.moveTowards(180, 0.13f); // always moving backward (holding S)

        // if (!level().isClientSide) {
        //     // Simple forward movement
        //     this.zza = 1.0f; // forward
        //     this.xxa = 0.0f; // no strafe
        //     this.setYRot(90); // face east, or rotate as needed
        //     this.setSprinting(true); // affects speed multiplier
        // }
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        RLOperatorRegistry.register(this);
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        RLOperatorRegistry.unregister(this);
    }

    // @Override
    // public void travel(Vec3 travelVector) {
    //     this.setSpeed((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED));
    //     super.travel(travelVector); // this applies zza/xxa/yya
    // }
}
