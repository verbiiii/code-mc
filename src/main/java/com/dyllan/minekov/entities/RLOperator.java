package com.dyllan.minekov.entities;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

public class RLOperator extends AIOperator {
    public RLOperator(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        // No custom initialization for DumbOperator
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        // TODO: RL goal
        // this.goalSelector.addGoal(1, new WatchClosestVisiblePlayerGoal(this, 64.0D));
        // this.goalSelector.addGoal(1, new DumbGunAttackGoal(this));
    }
}
