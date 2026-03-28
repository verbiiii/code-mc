package com.verbii.minekov.client;

import ai.xlate.xos.XosNative;

import com.mojang.blaze3d.platform.NativeImage;
import com.verbii.minekov.Minekov;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * Runs the xos engine (ball app via JNI) when {@link #setRunSession(boolean) run session} is on;
 * copies each frame into a {@link DynamicTexture}. Pump the engine every frame with
 * {@link #pumpFrame}; blit into the panel with {@link #blitViewport} when the body is visible.
 */
@OnlyIn(Dist.CLIENT)
public final class XosViewportRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ResourceLocation TEX_LOC =
            ResourceLocation.fromNamespaceAndPath(Minekov.MODID, "dynamic/xos_viewport");

    /** Viewport texture opacity when the xos panel is not hovered (ignore xos per-pixel alpha for this). */
    private static final int VIEWPORT_ALPHA_IDLE = Math.round(255 * 0.6f);
    /** Viewport texture opacity when the xos panel is hovered. */
    private static final int VIEWPORT_ALPHA_HOVER = Math.round(255 * 0.8f);

    private static boolean libraryTried;
    private static boolean libraryOk;
    private static boolean engineRunning;

    /** User chose Run; cleared on Close or when leaving chat. */
    private static boolean runSession;

    private static int texW = -1;
    private static int texH = -1;

    private static NativeImage nativeImage;
    private static DynamicTexture dynamicTexture;

    /** Set each frame before {@link #pumpFrame} (chat) or background pump (not hovered). */
    private static boolean panelHovered;

    private XosViewportRuntime() {}

    /** Whether the mouse is over the xos panel (minimized strip or full window). Drives viewport α 60%/80%. */
    public static void setPanelHovered(boolean hovered) {
        panelHovered = hovered;
    }

    public static boolean isRunSession() {
        return runSession;
    }

    /**
     * Starts or stops the JNI session. Stopping calls {@link #disposeEngine()} to release native
     * state and the GPU texture.
     */
    public static void setRunSession(boolean active) {
        runSession = active;
        if (!active) {
            disposeEngine();
        }
    }

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
            LOGGER.warn("xos_java not loaded (java.library.path); viewport stays black", t);
        }
    }

    /**
     * Advance the native engine and upload the framebuffer (call every frame while chat is open and
     * {@link #isRunSession()}).
     */
    public static void pumpFrame(Minecraft mc, int guiBodyW, int guiBodyH) {
        if (!runSession) {
            return;
        }
        tryLoadLibrary();
        if (!libraryOk || guiBodyW < 1 || guiBodyH < 1) {
            return;
        }

        int fbW = framebufferWidth(mc, guiBodyW);
        int fbH = framebufferHeight(mc, guiBodyH);
        ensureEngineAndTexture(mc, fbW, fbH);

        XosNative.tick();

        ByteBuffer buf = XosNative.getFrameBuffer();
        if (buf == null || nativeImage == null) {
            return;
        }

        int need = fbW * fbH * 4;
        if (buf.capacity() < need) {
            return;
        }

        copyRgbaToImage(buf, fbW, fbH);
        dynamicTexture.upload();
    }

    /**
     * Blits the last uploaded frame into the panel body. Call after {@link #pumpFrame} the same
     * frame.
     * <p>
     * Uses the overload where {@code guiW}/{@code guiH} are the <em>destination</em> size and
     * {@code tw}/{@code th} define the full texture region to sample — otherwise Minecraft would only
     * draw the top-left {@code guiW}×{@code guiH} texels of a larger framebuffer.
     */
    public static boolean blitViewport(GuiGraphics g, int x, int y, int guiW, int guiH) {
        if (!runSession || !libraryOk || nativeImage == null || guiW < 1 || guiH < 1) {
            return false;
        }
        int tw = nativeImage.getWidth();
        int th = nativeImage.getHeight();
        g.blit(TEX_LOC, x, y, guiW, guiH, 0f, 0f, tw, th, tw, th);
        return true;
    }

    public static void syncPointer(
            Minecraft mc, double mouseX, double mouseY, int bodyLeft, int bodyTop, int bodyW, int bodyH) {
        if (!libraryOk || !engineRunning || !runSession || bodyW < 1 || bodyH < 1) {
            return;
        }
        if (mouseX >= bodyLeft
                && mouseX < bodyLeft + bodyW
                && mouseY >= bodyTop
                && mouseY < bodyTop + bodyH) {
            float s = (float) effectiveGuiScale(mc);
            float lx = (float) (mouseX - bodyLeft) * s;
            float ly = (float) (mouseY - bodyTop) * s;
            XosNative.onMouseMove(lx, ly);
        }
    }

    public static void onMouseDownInBody() {
        if (!libraryOk || !engineRunning || !runSession) {
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
        runSession = false;
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
        panelHovered = false;
    }

    private static void ensureEngineAndTexture(Minecraft mc, int fbW, int fbH) {
        if (!engineRunning) {
            XosNative.init(fbW, fbH);
            engineRunning = true;
            texW = fbW;
            texH = fbH;
            rebuildTexture(mc, fbW, fbH);
            return;
        }
        if (fbW != texW || fbH != texH) {
            XosNative.resize(fbW, fbH);
            texW = fbW;
            texH = fbH;
            rebuildTexture(mc, fbW, fbH);
        }
    }

    private static double effectiveGuiScale(Minecraft mc) {
        double s = mc.getWindow().getGuiScale();
        if (s <= 0.0 || Double.isNaN(s)) {
            return 1.0;
        }
        return s;
    }

    private static int framebufferWidth(Minecraft mc, int guiW) {
        return Math.max(1, (int) Math.round(guiW * effectiveGuiScale(mc)));
    }

    private static int framebufferHeight(Minecraft mc, int guiH) {
        return Math.max(1, (int) Math.round(guiH * effectiveGuiScale(mc)));
    }

    private static void rebuildTexture(Minecraft mc, int w, int h) {
        mc.getTextureManager().release(TEX_LOC);
        dynamicTexture = null;
        nativeImage = null;

        nativeImage = new NativeImage(w, h, false);
        dynamicTexture = new DynamicTexture(nativeImage);
        mc.getTextureManager().register(TEX_LOC, dynamicTexture);
    }

    /**
     * {@link NativeImage#setPixelRGBA} expects <strong>ABGR</strong> (Minecraft’s convention).
     */
    private static int packAbgr(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | (b << 16) | (g << 8) | r;
    }

    /**
     * Ignore xos alpha for <em>window</em> opacity — only {@link #VIEWPORT_ALPHA_IDLE} /
     * {@link #VIEWPORT_ALPHA_HOVER} apply. Use xos alpha only to composite RGB onto black (glyph edges +
     * “empty” = black), then store that RGB with uniform output alpha.
     */
    private static void copyRgbaToImage(ByteBuffer buf, int w, int h) {
        int aOut = panelHovered ? VIEWPORT_ALPHA_HOVER : VIEWPORT_ALPHA_IDLE;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int base = (y * w + x) * 4;
                int r = buf.get(base) & 0xFF;
                int g = buf.get(base + 1) & 0xFF;
                int b = buf.get(base + 2) & 0xFF;
                int aIn = buf.get(base + 3) & 0xFF;
                int rp = (r * aIn + 127) / 255;
                int gp = (g * aIn + 127) / 255;
                int bp = (b * aIn + 127) / 255;
                nativeImage.setPixelRGBA(x, y, packAbgr(aOut, rp, gp, bp));
            }
        }
    }
}
