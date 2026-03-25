package com.verbii.minekov.client;

import com.verbii.minekov.entities.AIOperator;
import com.verbii.minekov.entities.RLOperator;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

public class AIOperatorRenderer<T extends AIOperator> extends HumanoidMobRenderer<T, HumanoidModel<T>> {
    private static final ResourceLocation DEFAULT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minekov", "textures/entity/ai_operator.png");

    public AIOperatorRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        if (entity instanceof RLOperator rl) {
            int gid = rl.getTrainingGroupId();
            if (gid >= 0) {
                return GroupTextureCache.getOrCreate(gid);
            }
        }
        return DEFAULT_TEXTURE;
    }
}
