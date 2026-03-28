package com.verbii.minekov.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.verbii.minekov.Minekov;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * xos viewport placeholder on {@link ChatScreen}: draggable neon title bar, resizable body,
 * minimize to title-only, synced 60%→100% opacity on hover (black fill + neon chrome).
 */
@Mod.EventBusSubscriber(modid = Minekov.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class XosViewportOverlay {

    private XosViewportOverlay() {}

    /** Electric lime — border + title bar */
    private static final int NEON_RGB = 0x39FF14;

    private static final int TITLE_BAR_H = 16;
    private static final int MINIMIZE_BTN = 14;
    private static final int MINIMIZE_PAD = 2;
    private static final int BORDER_PX = 2;
    private static final int HANDLE_CORNER = 8;
    private static final int HANDLE_EDGE = 6;

    /** Default body ~26% of screen (20% + 30%). */
    private static final float DEFAULT_BODY_FRAC = 0.26f;

    private static final int MIN_PANEL_W = 120;
    private static final int MIN_CONTENT_H = 72;

    private static final float ALPHA_IDLE = 0.6f;
    private static final float ALPHA_HOVER = 1.0f;
    private static final float SMOOTH = 0.18f;

    private static float smoothedAlpha = ALPHA_IDLE;

    private static int panelX;
    private static int panelY;
    private static int panelW;
    /** Black viewport height (not including title bar). */
    private static int contentH;
    private static boolean layoutReady;
    private static boolean minimized;

    private enum DragMode {
        NONE,
        MOVE,
        RESIZE_N,
        RESIZE_S,
        RESIZE_E,
        RESIZE_W,
        RESIZE_NE,
        RESIZE_NW,
        RESIZE_SE,
        RESIZE_SW
    }

    private static DragMode dragMode = DragMode.NONE;

    /** Move: grab offset */
    private static int moveGrabPanelX;
    private static int moveGrabPanelY;
    private static double moveGrabMouseX;
    private static double moveGrabMouseY;

    /** Resize: geometry at mouse-down */
    private static int anchorPanelX;
    private static int anchorPanelY;
    private static int anchorPanelW;
    private static int anchorContentH;
    private static double anchorMouseX;
    private static double anchorMouseY;

    private static int neonArgb(float alpha) {
        int a = Math.min(255, Math.max(0, Math.round(alpha * 255.0f)));
        return (a << 24) | (NEON_RGB & 0x00FFFFFF);
    }

    private static int blackArgb(float alpha) {
        int a = Math.min(255, Math.max(0, Math.round(alpha * 255.0f)));
        return (a << 24);
    }

    private static void ensureLayout(int sw, int sh) {
        if (layoutReady) {
            return;
        }
        int margin = Math.max(8, (int) (Math.min(sw, sh) * 0.015f));
        panelX = margin;
        panelY = margin;
        panelW = Math.max(MIN_PANEL_W, (int) (sw * DEFAULT_BODY_FRAC));
        contentH = Math.max(MIN_CONTENT_H, (int) (sh * DEFAULT_BODY_FRAC));
        layoutReady = true;
    }

    private static int totalPanelH() {
        return TITLE_BAR_H + (minimized ? 0 : contentH);
    }

    private static void clampToScreen(int sw, int sh) {
        panelW = Math.max(MIN_PANEL_W, panelW);
        contentH = Math.max(MIN_CONTENT_H, contentH);
        int th = totalPanelH();
        panelX = Mth.clamp(panelX, 0, Math.max(0, sw - panelW));
        panelY = Mth.clamp(panelY, 0, Math.max(0, sh - th));
    }

    private static int minimizeBtnX() {
        return panelX + panelW - MINIMIZE_BTN - MINIMIZE_PAD;
    }

    private static int minimizeBtnY() {
        return panelY + (TITLE_BAR_H - MINIMIZE_BTN) / 2;
    }

    private static boolean inMinimizeBtn(double mx, double my) {
        int bx = minimizeBtnX();
        int by = minimizeBtnY();
        return mx >= bx && mx < bx + MINIMIZE_BTN && my >= by && my < by + MINIMIZE_BTN;
    }

    private static boolean inTitleBarDragRegion(double mx, double my) {
        if (mx < panelX || mx >= panelX + panelW || my < panelY || my >= panelY + TITLE_BAR_H) {
            return false;
        }
        return !inMinimizeBtn(mx, my);
    }

    private static DragMode hitTestResize(double mx, double my) {
        int px = panelX;
        int py = panelY;
        int pw = panelW;
        int ph = minimized ? TITLE_BAR_H : totalPanelH();

        boolean onN = my >= py && my < py + HANDLE_EDGE;
        boolean onS = my >= py + ph - HANDLE_EDGE && my < py + ph;
        boolean onW = mx >= px && mx < px + HANDLE_EDGE;
        boolean onE = mx >= px + pw - HANDLE_EDGE && mx < px + pw;

        if (minimized) {
            if (onW && onN) {
                return DragMode.RESIZE_NW;
            }
            if (onE && onN) {
                return DragMode.RESIZE_NE;
            }
            if (onW && onS) {
                return DragMode.RESIZE_SW;
            }
            if (onE && onS) {
                return DragMode.RESIZE_SE;
            }
            if (onW) {
                return DragMode.RESIZE_W;
            }
            if (onE) {
                return DragMode.RESIZE_E;
            }
            return DragMode.NONE;
        }

        if (onN && onW && mx < px + HANDLE_CORNER && my < py + HANDLE_CORNER) {
            return DragMode.RESIZE_NW;
        }
        if (onN && onE && mx >= px + pw - HANDLE_CORNER && my < py + HANDLE_CORNER) {
            return DragMode.RESIZE_NE;
        }
        if (onS && onW && mx < px + HANDLE_CORNER && my >= py + ph - HANDLE_CORNER) {
            return DragMode.RESIZE_SW;
        }
        if (onS && onE && mx >= px + pw - HANDLE_CORNER && my >= py + ph - HANDLE_CORNER) {
            return DragMode.RESIZE_SE;
        }
        if (onN) {
            return DragMode.RESIZE_N;
        }
        if (onS) {
            return DragMode.RESIZE_S;
        }
        if (onW) {
            return DragMode.RESIZE_W;
        }
        if (onE) {
            return DragMode.RESIZE_E;
        }
        return DragMode.NONE;
    }

    private static boolean panelContains(double mx, double my) {
        int h = totalPanelH();
        return mx >= panelX && mx < panelX + panelW && my >= panelY && my < panelY + h;
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        int sw = event.getScreen().width;
        int sh = event.getScreen().height;
        ensureLayout(sw, sh);
        clampToScreen(sw, sh);

        double mx = mc.mouseHandler.xpos() * (double) sw / (double) mc.getWindow().getScreenWidth();
        double my = mc.mouseHandler.ypos() * (double) sh / (double) mc.getWindow().getScreenHeight();

        boolean hover = panelContains(mx, my);
        float target = hover ? ALPHA_HOVER : ALPHA_IDLE;
        smoothedAlpha += (target - smoothedAlpha) * SMOOTH;

        float a = smoothedAlpha;
        int neon = neonArgb(a);
        int blk = blackArgb(a);

        GuiGraphics g = event.getGuiGraphics();
        int ox = panelX;
        int oy = panelY;
        int ow = panelW;
        int th = TITLE_BAR_H;
        int bodyH = minimized ? 0 : contentH;
        int oh = th + bodyH;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        // Title bar (neon)
        g.fill(ox, oy, ox + ow, oy + th, neon);

        // Minimize button chip (slightly darker for contrast)
        int bx = minimizeBtnX();
        int by = minimizeBtnY();
        int chip = (Math.min(255, Math.round(a * 255)) << 24) | (0x228822 & 0xFFFFFF);
        g.fill(bx, by, bx + MINIMIZE_BTN, by + MINIMIZE_BTN, chip);
        int dashA = Math.min(255, Math.round(a * 255));
        int dash = (dashA << 24) | 0xFFFFFF;
        int dw = MINIMIZE_BTN - 6;
        g.fill(bx + 3, by + MINIMIZE_BTN / 2, bx + 3 + dw, by + MINIMIZE_BTN / 2 + 2, dash);

        // Black viewport
        if (!minimized && bodyH > 0) {
            g.fill(ox, oy + th, ox + ow, oy + th + bodyH, blk);
        }

        // Neon border (2 px), same alpha as chrome
        g.fill(ox, oy, ox + ow, oy + BORDER_PX, neon);
        g.fill(ox, oy + oh - BORDER_PX, ox + ow, oy + oh, neon);
        g.fill(ox, oy, ox + BORDER_PX, oy + oh, neon);
        g.fill(ox + ow - BORDER_PX, oy, ox + ow, oy + oh, neon);

        RenderSystem.disableBlend();
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen) || event.getButton() != 0) {
            return;
        }
        int sw = event.getScreen().width;
        int sh = event.getScreen().height;
        ensureLayout(sw, sh);

        double mx = event.getMouseX();
        double my = event.getMouseY();

        if (!panelContains(mx, my)) {
            return;
        }

        if (inMinimizeBtn(mx, my)) {
            minimized = !minimized;
            dragMode = DragMode.NONE;
            event.setCanceled(true);
            return;
        }

        DragMode r = hitTestResize(mx, my);
        if (r != DragMode.NONE) {
            dragMode = r;
            anchorPanelX = panelX;
            anchorPanelY = panelY;
            anchorPanelW = panelW;
            anchorContentH = contentH;
            anchorMouseX = mx;
            anchorMouseY = my;
            event.setCanceled(true);
            return;
        }

        if (inTitleBarDragRegion(mx, my)) {
            dragMode = DragMode.MOVE;
            moveGrabPanelX = panelX;
            moveGrabPanelY = panelY;
            moveGrabMouseX = mx;
            moveGrabMouseY = my;
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen) || event.getButton() != 0) {
            return;
        }
        dragMode = DragMode.NONE;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof ChatScreen)) {
            smoothedAlpha = ALPHA_IDLE;
            dragMode = DragMode.NONE;
            return;
        }

        long win = mc.getWindow().getWindow();
        if (GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            dragMode = DragMode.NONE;
            return;
        }

        if (dragMode == DragMode.NONE) {
            return;
        }

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        double mx = mc.mouseHandler.xpos() * (double) sw / (double) mc.getWindow().getScreenWidth();
        double my = mc.mouseHandler.ypos() * (double) sh / (double) mc.getWindow().getScreenHeight();

        boolean wasMin = minimized;

        switch (dragMode) {
            case MOVE -> {
                panelX = (int) Math.round(moveGrabPanelX + (mx - moveGrabMouseX));
                panelY = (int) Math.round(moveGrabPanelY + (my - moveGrabMouseY));
            }
            case RESIZE_N -> {
                if (!wasMin) {
                    int bottom = anchorPanelY + TITLE_BAR_H + anchorContentH;
                    int newY = (int) Math.round(Mth.clamp(my, 0, bottom - TITLE_BAR_H - MIN_CONTENT_H));
                    panelY = newY;
                    contentH = bottom - TITLE_BAR_H - panelY;
                }
            }
            case RESIZE_S -> {
                if (!wasMin) {
                    contentH = (int) Math.round(Mth.clamp(
                            my - anchorPanelY - TITLE_BAR_H,
                            MIN_CONTENT_H,
                            sh));
                }
            }
            case RESIZE_E -> {
                panelW = (int) Math.round(Mth.clamp(
                        anchorPanelW + (mx - anchorMouseX),
                        MIN_PANEL_W,
                        sw - anchorPanelX));
            }
            case RESIZE_W -> {
                int right = anchorPanelX + anchorPanelW;
                int newX = (int) Math.round(Mth.clamp(mx, 0, right - MIN_PANEL_W));
                panelX = newX;
                panelW = right - newX;
            }
            case RESIZE_NE -> {
                if (!wasMin) {
                    int bottom = anchorPanelY + TITLE_BAR_H + anchorContentH;
                    int newY = (int) Math.round(Mth.clamp(my, 0, bottom - TITLE_BAR_H - MIN_CONTENT_H));
                    panelY = newY;
                    contentH = bottom - TITLE_BAR_H - panelY;
                    // Left edge fixed at drag start
                    panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw - anchorPanelX));
                } else {
                    panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw - anchorPanelX));
                }
            }
            case RESIZE_NW -> {
                if (!wasMin) {
                    int bottom = anchorPanelY + TITLE_BAR_H + anchorContentH;
                    int right = anchorPanelX + anchorPanelW;
                    int newY = (int) Math.round(Mth.clamp(my, 0, bottom - TITLE_BAR_H - MIN_CONTENT_H));
                    int newX = (int) Math.round(Mth.clamp(mx, 0, right - MIN_PANEL_W));
                    panelY = newY;
                    panelX = newX;
                    contentH = bottom - TITLE_BAR_H - panelY;
                    panelW = right - newX;
                } else {
                    int right = anchorPanelX + anchorPanelW;
                    int newX = (int) Math.round(Mth.clamp(mx, 0, right - MIN_PANEL_W));
                    panelX = newX;
                    panelW = right - newX;
                }
            }
            case RESIZE_SE -> {
                if (!wasMin) {
                    // Top-left fixed
                    panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw - anchorPanelX));
                    contentH = (int) Math.round(Mth.clamp(
                            my - anchorPanelY - TITLE_BAR_H,
                            MIN_CONTENT_H,
                            sh));
                } else {
                    panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw - anchorPanelX));
                }
            }
            case RESIZE_SW -> {
                if (!wasMin) {
                    int right = anchorPanelX + anchorPanelW;
                    int newX = (int) Math.round(Mth.clamp(mx, 0, right - MIN_PANEL_W));
                    panelX = newX;
                    panelW = right - newX;
                    contentH = (int) Math.round(Mth.clamp(
                            my - anchorPanelY - TITLE_BAR_H,
                            MIN_CONTENT_H,
                            sh));
                } else {
                    int right = anchorPanelX + anchorPanelW;
                    int newX = (int) Math.round(Mth.clamp(mx, 0, right - MIN_PANEL_W));
                    panelX = newX;
                    panelW = right - newX;
                }
            }
            default -> {
            }
        }

        clampToScreen(sw, sh);
    }
}
