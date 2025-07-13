package com.dyllan.minekov.training;

import com.dyllan.minekov.Minekov;
import com.dyllan.minekov.entities.AIOperator;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber
public class TrainingIsolationHandler {

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (shouldCancelInteraction(event.getSource().getEntity(), event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (shouldCancelInteraction(event.getSource().getEntity(), event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onEntityHurtByGun(EntityHurtByGunEvent.Pre event) {
        LivingEntity attacker = event.getAttacker();
        Entity hurtEntity = event.getHurtEntity();
        if (shouldCancelInteraction(attacker, hurtEntity)) {
            event.setCanceled(true);
            return;
        }
    }

    /**
     * Centralized logic to check whether two entities can interact, used in collisions, projectiles, etc.
     */
    public static boolean shouldEntitiesInteract(Entity a, Entity b) {
        if (Minekov.trainingState == null) return true; // not in training mode

        // ai operators can always interact with non-ai operators
        if ((a instanceof AIOperator) != (b instanceof AIOperator)) return true;

        // if both are AIOperators, only allow if in same group
        if ((a instanceof AIOperator) && (b instanceof AIOperator)) {
            for (TrainingGroup group : Minekov.trainingState.getGroups()) {
                if (group.shouldInteract(a, b)) {
                    return true;
                }
            }

            return false;
        }

        return true;
    }

    /**
     * Returns true if the interaction should be canceled (i.e. entities should not interact)
     */
    public static boolean shouldCancelInteraction(Entity a, Entity b) {
        if (a == null || b == null) return false;
        return !shouldEntitiesInteract(a, b);
    }
}
