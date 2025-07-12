package com.dyllan.minekov.mixin;

import com.dyllan.minekov.training.TrainingIsolationHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

@Mixin(Level.class)
public abstract class LevelCollisionMixin {

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    public void minekov$filterEntityCollisions(Entity except, AABB area, Predicate<? super Entity> predicate, CallbackInfoReturnable<List<Entity>> cir) {
        if (except == null) return;
        
        List<Entity> originalEntities = cir.getReturnValue();
        List<Entity> filteredEntities = new ArrayList<>();
        
        for (Entity entity : originalEntities) {
            // Check if these entities should interact based on training isolation
            if (TrainingIsolationHandler.shouldEntitiesInteract(except, entity)) {
                filteredEntities.add(entity);
            } else {
                System.out.println("[Minekov] Blocking collision detection between " + except.getClass().getSimpleName() + " and " + entity.getClass().getSimpleName());
            }
        }
        
        cir.setReturnValue(filteredEntities);
    }
}
