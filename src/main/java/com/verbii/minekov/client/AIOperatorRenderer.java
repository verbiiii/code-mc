package com.verbii.minekov.client;

import com.verbii.minekov.entities.AIOperator;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class AIOperatorRenderer<T extends AIOperator> extends HumanoidMobRenderer<T, HumanoidModel<T>> {
    public AIOperatorRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return ResourceLocation.fromNamespaceAndPath("minekov", "textures/entity/ai_operator.png");
    }
}
