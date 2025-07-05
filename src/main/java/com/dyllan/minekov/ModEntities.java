package com.dyllan.minekov;

import com.dyllan.minekov.entities.AIOperator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = "minekov", bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, "minekov");

    public static final RegistryObject<EntityType<AIOperator>> AI_OPERATOR =
        ENTITY_TYPES.register("ai_operator",
            () -> EntityType.Builder.of(AIOperator::new, MobCategory.MONSTER)
                .sized(0.6f, 1.8f)
                .build(new ResourceLocation("minekov", "ai_operator").toString()));

    public static void register() {
        ENTITY_TYPES.register(FMLJavaModLoadingContext.get().getModEventBus());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreate(EntityAttributeCreationEvent event) {
        event.put(AI_OPERATOR.get(), AIOperator.createAttributes().build());
    }
}
