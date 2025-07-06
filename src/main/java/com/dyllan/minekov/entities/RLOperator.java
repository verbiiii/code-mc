package com.dyllan.minekov.entities;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.level.Level;

public class RLOperator extends AIOperator {
    public RLOperator(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        // entity wander
        this.goalSelector.addGoal(1, new RandomStrollGoal(this, 0.5D));

        // TODO: RL goal
        // this.goalSelector.addGoal(1, new WatchClosestVisiblePlayerGoal(this, 64.0D));
        // this.goalSelector.addGoal(1, new DumbGunAttackGoal(this));
    }

    // @Override
    // public void tick() {
    //     this.setMoveForward(true);
    //     this.setSprinting(true);
    //     super.tick();
    // }

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
