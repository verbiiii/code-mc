package com.verbii.minekov.client;

import ai.xlate.xos.XosNative;

import com.mojang.blaze3d.platform.NativeImage;
import com.verbii.minekov.Minekov;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * Runs the xos engine (ball app via JNI) at the viewport size and copies each frame into a
 * {@link DynamicTexture} for {@link GuiGraphics#blit}.
 */
@OnlyIn(Dist.CLIENT)
public final class XosViewportRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation TEX_LOC =
            ResourceLocation.fromNamespaceAndPath(Minekov.MODID, "dynamic/xos_viewport");

    private static boolean libraryTried;
    private static boolean libraryOk;
    private static boolean engineRunning;

    private static int texW = -1;
    private static int texH = -1;

    private static NativeImage nativeImage;
    private static DynamicTexture dynamicTexture;

    private XosViewportRuntime() {}

    private static void tryLoadLibrary() {
        if (libraryTried) {
            return;
        }
        libraryTried = true;
        try {
            XosNative.initLibraryFromPath();
            libraryOk = true;
        } catch (Throwable t) {
            libraryOk = false;
            LOGGER.warn("xos_jni not loaded (java.library.path); viewport stays black", t);
        }
    }

    /**
     * @return true if the framebuffer was drawn (caller should skip solid black fill)
     */
    public static boolean renderIntoViewport(GuiGraphics g, int x, int y, int w, int h) {
        tryLoadLibrary();
        if (!libraryOk || w < 1 || h < 1) {
            return false;
        }

        Minecraft mc = Minecraft.getInstance();
        ensureEngineAndTexture(mc, w, h);

        XosNative.tick();

        ByteBuffer buf = XosNative.getFrameBuffer();
        if (buf == null || nativeImage == null) {
            return false;
        }

        int need = w * h * 4;
        if (buf.capacity() < need) {
            return false;
        }

        copyRgbaToImage(buf, w, h);
        dynamicTexture.upload();

        g.blit(TEX_LOC, x, y, 0, 0f, 0f, w, h, w, h);
        return true;
    }

    public static void syncPointer(
            Minecraft mc, double mouseX, double mouseY, int bodyLeft, int bodyTop, int bodyW, int bodyH) {
        if (!libraryOk || !engineRunning || bodyW < 1 || bodyH < 1) {
            return;
        }
        if (mouseX >= bodyLeft
                && mouseX < bodyLeft + bodyW
                && mouseY >= bodyTop
                && mouseY < bodyTop + bodyH) {
            float lx = (float) (mouseX - bodyLeft);
            float ly = (float) (mouseY - bodyTop);
            XosNative.onMouseMove(lx, ly);
        }
    }

    public static void onMouseDownInBody() {
        if (!libraryOk || !engineRunning) {
            return;
        }
        XosNative.onMouseDown(0);
    }

    public static void onMouseUpLeft() {
        if (!libraryOk || !engineRunning) {
            return;
        }
        XosNative.onMouseUp(0);
    }

    /** Call when chat closes so the native engine and GPU texture can be released. */
    public static void disposeEngine() {
        if (!libraryOk || !engineRunning) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.getTextureManager().release(TEX_LOC);
        }
        dynamicTexture = null;
        nativeImage = null;
        texW = -1;
        texH = -1;
        engineRunning = false;
        try {
            XosNative.shutdown();
        } catch (Throwable ignored) {
        }
    }

    private static void ensureEngineAndTexture(Minecraft mc, int w, int h) {
        if (!engineRunning) {
            XosNative.init(w, h);
            engineRunning = true;
            texW = w;
            texH = h;
            rebuildTexture(mc, w, h);
            return;
        }
        if (w != texW || h != texH) {
            XosNative.resize(w, h);
            texW = w;
            texH = h;
            rebuildTexture(mc, w, h);
        }
    }

    private static void rebuildTexture(Minecraft mc, int w, int h) {
        mc.getTextureManager().release(TEX_LOC);
        dynamicTexture = null;
        nativeImage = null;

        nativeImage = new NativeImage(w, h, false);
        dynamicTexture = new DynamicTexture(nativeImage);
        mc.getTextureManager().register(TEX_LOC, dynamicTexture);
    }

    private static void copyRgbaToImage(ByteBuffer buf, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int base = (y * w + x) * 4;
                int r = buf.get(base) & 0xFF;
                int g = buf.get(base + 1) & 0xFF;
                int b = buf.get(base + 2) & 0xFF;
                int a = buf.get(base + 3) & 0xFF;
                nativeImage.setPixelRGBA(x, y, FastColor.ARGB32.color(a, r, g, b));
            }
        }
    }
}
