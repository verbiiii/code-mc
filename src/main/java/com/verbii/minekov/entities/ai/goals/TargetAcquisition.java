package com.verbii.minekov.entities.ai.goals;

import com.verbii.minekov.Minekov;
import com.verbii.minekov.entities.AIOperator;
import com.verbii.minekov.training.TrainingGroup;
import com.verbii.minekov.training.TrainingState;

import net.minecraft.world.entity.LivingEntity;

/**
 * Utility class for target acquisition that respects TrainingGroup boundaries.
 * This ensures entities only target others within the same training group.
 */
public class TargetAcquisition {
    
    /**
     * Finds a target for the given AIOperator within the same training group.
     * Returns null if no valid target is found or if not in a training session.
     */
    public static LivingEntity findTarget(AIOperator operator) {
        TrainingState trainingState = Minekov.trainingState;
        
        // If we're not in a training session, return null
        if (trainingState == null) {
            return null;
        }
        
        // Find the training group that contains this operator
        TrainingGroup operatorGroup = null;
        for (TrainingGroup group : trainingState.getGroups()) {
            if (group.contains(operator)) {
                operatorGroup = group;
                break;
            }
        }
        
        // If operator is not in any training group, return null
        if (operatorGroup == null) {
            return null;
        }
        
        // Use the training group's target acquisition method
        return operatorGroup.chooseRandomTarget(operator);
    }
}
