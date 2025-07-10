package com.dyllan.minekov.training;

import com.dyllan.minekov.Minekov;
import com.dyllan.minekov.entities.AIOperator;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class TrainingIsolationHandler {

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (Minekov.trainingState == null) return; // not in training mode, skip

        Entity target = event.getEntity();
        Entity source = event.getSource().getEntity();

        if (source == null || target == null) return;

        if (source instanceof AIOperator || target instanceof AIOperator) {
            for (TrainingGroup group : Minekov.trainingState.getGroups()) {
                if (!group.shouldInteract(source, target)) {
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }

    /**
     * Centralized logic to check whether two entities can interact, used in collisions and targeting.
     */
    public static boolean shouldEntitiesInteract(Entity a, Entity b) {
        if (Minekov.trainingState == null) return true; // not in training mode

        if (!(a instanceof AIOperator) && !(b instanceof AIOperator)) return true;

        for (TrainingGroup group : Minekov.trainingState.getGroups()) {
            if (!group.shouldInteract(a, b)) {
                return false;
            }
        }

        return true;
    }
}
