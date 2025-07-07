package com.dyllan.minekov;

import com.dyllan.minekov.entities.AIOperator;
import com.dyllan.minekov.entities.DumbOperator;
import com.dyllan.minekov.entities.RLOperator;

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

    // Register DumbOperator
    public static final RegistryObject<EntityType<DumbOperator>> DUMB_OPERATOR =
        ENTITY_TYPES.register("dumb_operator",
            () -> EntityType.Builder.of(DumbOperator::new, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .clientTrackingRange((int) DumbOperator.MAX_DISTANCE)
                .updateInterval(1)
                .build(new ResourceLocation("minekov", "dumb_operator").toString()));

    // Register AIOperator
    public static final RegistryObject<EntityType<RLOperator>> RL_OPERATOR =
        ENTITY_TYPES.register("rl_operator",
            () -> EntityType.Builder.of(RLOperator::new, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .clientTrackingRange((int) RLOperator.MAX_DISTANCE)
                .updateInterval(1)
                .build(new ResourceLocation("minekov", "rl_operator").toString()));

    public static void register() {
        ENTITY_TYPES.register(FMLJavaModLoadingContext.get().getModEventBus());
    }

    @SubscribeEvent
    public static void onEntityAttributeCreate(EntityAttributeCreationEvent event) {
        event.put(DUMB_OPERATOR.get(), DumbOperator.createAttributes().build());
        event.put(RL_OPERATOR.get(), AIOperator.createAttributes().build());
    }
}
