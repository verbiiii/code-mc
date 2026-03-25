package com.verbii.minekov.client;

import com.verbii.minekov.ModEntities;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "minekov", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientSetup {
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.DUMB_OPERATOR.get(), AIOperatorRenderer::new);
        event.registerEntityRenderer(ModEntities.RL_OPERATOR.get(), AIOperatorRenderer::new);
    }
}
