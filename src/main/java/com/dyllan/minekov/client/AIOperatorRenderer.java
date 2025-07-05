package com.dyllan.minekov.client;

import com.dyllan.minekov.entities.AIOperator;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class AIOperatorRenderer extends HumanoidMobRenderer<AIOperator, HumanoidModel<AIOperator>> {
    public AIOperatorRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(AIOperator entity) {
        return ResourceLocation.fromNamespaceAndPath("minekov", "textures/entity/ai_operator.png");
    }
}
