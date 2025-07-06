package com.dyllan.minekov.entities;

import com.dyllan.minekov.entities.ai.goals.DumbGunAttackGoal;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.Level;

public class DumbOperator extends AIOperator {
    public DumbOperator(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        // No custom initialization for DumbOperator
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();

        // this.goalSelector.addGoal(1, new WatchClosestVisiblePlayerGoal(this, 64.0D));
        this.goalSelector.addGoal(1, new DumbGunAttackGoal(this));
    }
}
