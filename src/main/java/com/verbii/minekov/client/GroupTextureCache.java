package com.verbii.minekov.client;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

/**
 * Solid-fill player-shaped skins tinted per training group (FFA arena index).
 */
@OnlyIn(Dist.CLIENT)
public final class GroupTextureCache {
    private static final ResourceLocation FALLBACK =
            ResourceLocation.fromNamespaceAndPath("minekov", "textures/entity/ai_operator.png");
    private static final Map<Integer, ResourceLocation> CACHE = new HashMap<>();

    private GroupTextureCache() {}

    public static ResourceLocation getOrCreate(int groupId) {
        if (groupId < 0) {
            return FALLBACK;
        }
        return CACHE.computeIfAbsent(groupId, GroupTextureCache::buildGroupTexture);
    }

    private static ResourceLocation buildGroupTexture(int groupId) {
        int rgb = rgbForGroupIndex(groupId);
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        NativeImage img = new NativeImage(64, 64, false);
        int argb = FastColor.ARGB32.color(255, r, g, b);
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                img.setPixelRGBA(x, y, argb);
            }
        }
        ResourceLocation loc = ResourceLocation.fromNamespaceAndPath("minekov", "textures/entity/dynamic/group_" + groupId);
        DynamicTexture tex = new DynamicTexture(img);
        tex.upload();
        Minecraft.getInstance().getTextureManager().register(loc, tex);
        return loc;
    }

    /** Golden-ratio hue steps so neighboring group ids get distinct colors. */
    private static int rgbForGroupIndex(int index) {
        float hue = (index * 0.6180339887f) % 1.0f;
        return Color.HSBtoRGB(hue, 0.65f, 0.9f) & 0xFFFFFF;
    }
}
