package com.verbii.minekov.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.verbii.minekov.Minekov;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * xos viewport placeholder: when chat is open ({@link ChatScreen}), draws a semi-transparent black
 * rectangle in the top-left (~20% of the screen), easing to full opacity on hover.
 */
@Mod.EventBusSubscriber(modid = Minekov.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class XosViewportOverlay {

    private XosViewportOverlay() {}

    private static final float ALPHA_IDLE = 0.9f;
    private static final float ALPHA_HOVER = 1.0f;
    /** Per-frame smoothing toward hover / idle (higher = snappier). */
    private static final float SMOOTH = 0.18f;

    private static float smoothedAlpha = ALPHA_IDLE;

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int sw = event.getScreen().width;
        int sh = event.getScreen().height;

        int margin = Math.max(6, (int) (Math.min(sw, sh) * 0.015f));
        int vw = Math.max(32, (int) (sw * 0.2f));
        int vh = Math.max(32, (int) (sh * 0.2f));
        int x1 = margin;
        int y1 = margin;
        int x2 = x1 + vw;
        int y2 = y1 + vh;

        double mx = mc.mouseHandler.xpos() * (double) sw / (double) mc.getWindow().getScreenWidth();
        double my = mc.mouseHandler.ypos() * (double) sh / (double) mc.getWindow().getScreenHeight();

        boolean hover = mx >= x1 && mx < x2 && my >= y1 && my < y2;
        float target = hover ? ALPHA_HOVER : ALPHA_IDLE;
        smoothedAlpha += (target - smoothedAlpha) * SMOOTH;

        int a = Math.min(255, Math.max(0, Math.round(smoothedAlpha * 255.0f)));
        int argb = (a << 24); // opaque black in RGB; alpha in A

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        event.getGuiGraphics().fill(x1, y1, x2, y2, argb);
        RenderSystem.disableBlend();
    }

    /** Snap smoothing when chat closes so the next open starts from idle opacity. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof ChatScreen)) {
            smoothedAlpha = ALPHA_IDLE;
        }
    }
}
