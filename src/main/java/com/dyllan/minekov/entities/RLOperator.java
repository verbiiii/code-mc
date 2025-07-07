package com.dyllan.minekov.entities;

import com.dyllan.minekov.entities.ai.goals.WatchClosestVisiblePlayerGoal;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

public class RLOperator extends AIOperator {
    public RLOperator(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        // entity wander
        // this.goalSelector.addGoal(1, new RandomStrollGoal(this, 0.5D));

        // TODO: RL goal
        // this.goalSelector.addGoal(1, new WatchClosestVisiblePlayerGoal(this, 64.0D));
        // this.goalSelector.addGoal(1, new DumbGunAttackGoal(this));
    }

    @Override
    public void tick() {
        super.tick();

        this.setSprinting(true);
        // set delta movement to forward
        this.setDeltaMovement(this.getDeltaMovement().add(0, 0, 0.1)); // Adjust forward speed as needed
    }

    // @Override
    // public void tick() {
    //     super.tick();

    //     if (!level().isClientSide) {
    //         // Simple forward movement
    //         this.zza = 1.0f; // forward
    //         this.xxa = 0.0f; // no strafe
    //         this.setYRot(90); // face east, or rotate as needed
    //         this.setSprinting(true); // affects speed multiplier
    //     }
    // }

    // @Override
    // public void travel(Vec3 travelVector) {
    //     this.setSpeed((float) this.getAttributeValue(Attributes.MOVEMENT_SPEED));
    //     super.travel(travelVector); // this applies zza/xxa/yya
    // }
}
