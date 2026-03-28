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

    /** Softer mint / light neon — title bar, minus sign (border uses same hue, lower alpha). */
    private static final int NEON_RGB = 0x7DD87A;

    /** ~5% shorter than prior 8px */
    private static final int TITLE_BAR_H = 7;
    private static final int MINIMIZE_BTN = 6;
    private static final int MINIMIZE_PAD = 1;
    /** 1 logical px; drawn with reduced alpha so it reads ~30% thinner */
    private static final int BORDER_PX = 1;
    private static final float BORDER_ALPHA_MUL = 0.65f;
    private static final int HANDLE_CORNER = 6;
    private static final int HANDLE_EDGE = 4;

    /** Default body size; ~20% larger than prior 0.26 → 0.312 */
    private static final float DEFAULT_BODY_FRAC = 0.312f;

    private static final int MIN_PANEL_W = 120;
    private static final int MIN_CONTENT_H = 72;

    /** At least this fraction of the panel must stay on-screen (10%). */
    private static final double MIN_VISIBLE_FRAC = 0.10;

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

    /** Double-click title bar: nearly full screen with 10% inset; double-click again restores. */
    private static boolean maximized;
    private static int restorePanelX;
    private static int restorePanelY;
    private static int restorePanelW;
    private static int restoreContentH;
    private static boolean restoreMinimized;

    private static boolean titleBarAwaitingSecondClick;
    private static long titleBarFirstClickNs;
    private static double titleBarFirstClickMx;
    private static double titleBarFirstClickMy;
    private static final long TITLE_DOUBLE_CLICK_NS = 400_000_000L;
    private static final double TITLE_DOUBLE_CLICK_MAX_DIST = 12.0;

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

    /** GLFW standard cursors for resize affordances (lazy-init). */
    private static long cursorResizeH;
    private static long cursorResizeV;
    private static long cursorResizeNwse;
    private static long cursorResizeNesw;
    private static long cursorHand;
    private static boolean cursorsInitialized;

    private static void ensureResizeCursors() {
        if (cursorsInitialized) {
            return;
        }
        cursorsInitialized = true;
        cursorResizeH = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HRESIZE_CURSOR);
        cursorResizeV = GLFW.glfwCreateStandardCursor(GLFW.GLFW_VRESIZE_CURSOR);
        cursorResizeNwse = GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_NWSE_CURSOR);
        cursorResizeNesw = GLFW.glfwCreateStandardCursor(GLFW.GLFW_RESIZE_NESW_CURSOR);
        cursorHand = GLFW.glfwCreateStandardCursor(GLFW.GLFW_POINTING_HAND_CURSOR);
        if (cursorHand == 0) {
            cursorHand = GLFW.glfwCreateStandardCursor(GLFW.GLFW_HAND_CURSOR);
        }
    }

    /** @return GLFW cursor handle, or 0 for OS default arrow */
    private static long glfwCursorForMode(DragMode m) {
        return switch (m) {
            case MOVE -> 0L;
            case RESIZE_E, RESIZE_W -> cursorResizeH;
            case RESIZE_N, RESIZE_S -> cursorResizeV;
            case RESIZE_NW, RESIZE_SE -> cursorResizeNwse;
            case RESIZE_NE, RESIZE_SW -> cursorResizeNesw;
            default -> 0L;
        };
    }

    private static void applyGlfwCursor(Minecraft mc, long cursor) {
        long win = mc.getWindow().getWindow();
        GLFW.glfwSetCursor(win, cursor);
    }

    /**
     * While chat + panel are active, sets resize/move/hand cursors; otherwise restores default.
     */
    private static void updateResizeCursor(Minecraft mc, int sw, int sh, double mx, double my) {
        ensureResizeCursors();
        if (dragMode != DragMode.NONE) {
            long c = glfwCursorForMode(dragMode);
            applyGlfwCursor(mc, c);
            return;
        }
        if (!panelContains(mx, my)) {
            applyGlfwCursor(mc, 0L);
            return;
        }
        if (inMinimizeBtn(mx, my)) {
            applyGlfwCursor(mc, cursorHand);
            return;
        }
        DragMode edge = hitTestResize(mx, my);
        if (edge != DragMode.NONE) {
            applyGlfwCursor(mc, glfwCursorForMode(edge));
            return;
        }
        if (inTitleBarDragRegion(mx, my)) {
            applyGlfwCursor(mc, 0L);
            return;
        }
        applyGlfwCursor(mc, 0L);
    }

    private static int neonArgb(float alpha) {
        int a = Math.min(255, Math.max(0, Math.round(alpha * 255.0f)));
        return (a << 24) | (NEON_RGB & 0x00FFFFFF);
    }

    /** Border stroke: same hue, lower alpha so the frame looks thinner / less loud */
    private static int neonBorderArgb(float panelAlpha) {
        float a = Mth.clamp(panelAlpha * BORDER_ALPHA_MUL, 0.0f, 1.0f);
        int ai = Math.min(255, Math.max(0, Math.round(a * 255.0f)));
        return (ai << 24) | (NEON_RGB & 0x00FFFFFF);
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

    /**
     * Allow most of the panel off-screen, but keep at least {@value #MIN_VISIBLE_FRAC} of width and
     * height intersecting the GUI viewport.
     */
    private static void clampPartialOnScreen(int sw, int sh) {
        panelW = Math.max(MIN_PANEL_W, panelW);
        contentH = Math.max(MIN_CONTENT_H, contentH);
        int th = totalPanelH();

        int minVisW = Math.max(1, (int) Math.ceil(panelW * MIN_VISIBLE_FRAC));
        int minVisH = Math.max(1, (int) Math.ceil(th * MIN_VISIBLE_FRAC));

        int minX = -(panelW - minVisW);
        int maxX = sw - minVisW;
        int minY = -(th - minVisH);
        int maxY = sh - minVisH;

        panelX = Mth.clamp(panelX, minX, maxX);
        panelY = Mth.clamp(panelY, minY, maxY);
    }

    private static void toggleMaximizedLayout(int sw, int sh) {
        if (!maximized) {
            restorePanelX = panelX;
            restorePanelY = panelY;
            restorePanelW = panelW;
            restoreContentH = contentH;
            restoreMinimized = minimized;
            minimized = false;
            int insetX = Math.max(1, (int) Math.round(sw * 0.10));
            int insetY = Math.max(1, (int) Math.round(sh * 0.10));
            panelX = insetX;
            panelY = insetY;
            panelW = Math.max(MIN_PANEL_W, sw - 2 * insetX);
            int innerH = Math.max(TITLE_BAR_H + MIN_CONTENT_H, (int) Math.round(sh * 0.80));
            contentH = Math.max(MIN_CONTENT_H, innerH - TITLE_BAR_H);
            maximized = true;
        } else {
            panelX = restorePanelX;
            panelY = restorePanelY;
            panelW = restorePanelW;
            contentH = restoreContentH;
            minimized = restoreMinimized;
            maximized = false;
        }
        clampPartialOnScreen(sw, sh);
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

    private static double scaledMouseX(Minecraft mc, int sw) {
        return mc.mouseHandler.xpos() * (double) sw / (double) mc.getWindow().getScreenWidth();
    }

    private static double scaledMouseY(Minecraft mc, int sh) {
        return mc.mouseHandler.ypos() * (double) sh / (double) mc.getWindow().getScreenHeight();
    }

    /** Drag/resize runs every frame while chat is open — fixes 20Hz tick choppiness. */
    private static void applyDragOrResize(Minecraft mc, int sw, int sh) {
        long win = mc.getWindow().getWindow();
        if (GLFW.glfwGetMouseButton(win, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            dragMode = DragMode.NONE;
            return;
        }
        if (dragMode == DragMode.NONE) {
            return;
        }

        double mx = scaledMouseX(mc, sw);
        double my = scaledMouseY(mc, sh);
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
                        sw * 2));
            }
            case RESIZE_W -> {
                int right = anchorPanelX + anchorPanelW;
                int newX = (int) Math.round(Mth.clamp(mx, -sw, right - MIN_PANEL_W));
                panelX = newX;
                panelW = right - newX;
            }
            case RESIZE_NE -> {
                if (!wasMin) {
                    int bottom = anchorPanelY + TITLE_BAR_H + anchorContentH;
                    int newY = (int) Math.round(Mth.clamp(my, 0, bottom - TITLE_BAR_H - MIN_CONTENT_H));
                    panelY = newY;
                    contentH = bottom - TITLE_BAR_H - panelY;
                    panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw * 2));
                } else {
                    panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw * 2));
                }
            }
            case RESIZE_NW -> {
                if (!wasMin) {
                    int bottom = anchorPanelY + TITLE_BAR_H + anchorContentH;
                    int right = anchorPanelX + anchorPanelW;
                    int newY = (int) Math.round(Mth.clamp(my, 0, bottom - TITLE_BAR_H - MIN_CONTENT_H));
                    int newX = (int) Math.round(Mth.clamp(mx, -sw, right - MIN_PANEL_W));
                    panelY = newY;
                    panelX = newX;
                    contentH = bottom - TITLE_BAR_H - panelY;
                    panelW = right - newX;
                } else {
                    int right = anchorPanelX + anchorPanelW;
                    int newX = (int) Math.round(Mth.clamp(mx, -sw, right - MIN_PANEL_W));
                    panelX = newX;
                    panelW = right - newX;
                }
            }
            case RESIZE_SE -> {
                if (!wasMin) {
                    panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw * 2));
                    contentH = (int) Math.round(Mth.clamp(
                            my - anchorPanelY - TITLE_BAR_H,
                            MIN_CONTENT_H,
                            sh));
                } else {
                    panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw * 2));
                }
            }
            case RESIZE_SW -> {
                if (!wasMin) {
                    int right = anchorPanelX + anchorPanelW;
                    int newX = (int) Math.round(Mth.clamp(mx, -sw, right - MIN_PANEL_W));
                    panelX = newX;
                    panelW = right - newX;
                    contentH = (int) Math.round(Mth.clamp(
                            my - anchorPanelY - TITLE_BAR_H,
                            MIN_CONTENT_H,
                            sh));
                } else {
                    int right = anchorPanelX + anchorPanelW;
                    int newX = (int) Math.round(Mth.clamp(mx, -sw, right - MIN_PANEL_W));
                    panelX = newX;
                    panelW = right - newX;
                }
            }
            default -> {
            }
        }

        clampPartialOnScreen(sw, sh);
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

        applyDragOrResize(mc, sw, sh);

        double mx = scaledMouseX(mc, sw);
        double my = scaledMouseY(mc, sh);

        boolean hover = panelContains(mx, my);
        float target = hover ? ALPHA_HOVER : ALPHA_IDLE;
        smoothedAlpha += (target - smoothedAlpha) * SMOOTH;

        float a = smoothedAlpha;
        int neon = neonArgb(a);
        int neonBorder = neonBorderArgb(a);
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

        // Minimize: black square + neon minus
        int bx = minimizeBtnX();
        int by = minimizeBtnY();
        int btnA = Math.min(255, Math.round(a * 255));
        int blackBtn = (btnA << 24);
        g.fill(bx, by, bx + MINIMIZE_BTN, by + MINIMIZE_BTN, blackBtn);
        int minus = neonArgb(a);
        int mw = MINIMIZE_BTN - 4;
        int mh = Math.max(1, MINIMIZE_BTN / 4);
        g.fill(bx + 2, by + MINIMIZE_BTN / 2 - mh / 2, bx + 2 + mw, by + MINIMIZE_BTN / 2 - mh / 2 + mh, minus);

        // Black viewport
        if (!minimized && bodyH > 0) {
            g.fill(ox, oy + th, ox + ow, oy + th + bodyH, blk);
        }

        // Neon border (lighter weight than title bar)
        g.fill(ox, oy, ox + ow, oy + BORDER_PX, neonBorder);
        g.fill(ox, oy + oh - BORDER_PX, ox + ow, oy + oh, neonBorder);
        g.fill(ox, oy, ox + BORDER_PX, oy + oh, neonBorder);
        g.fill(ox + ow - BORDER_PX, oy, ox + ow, oy + oh, neonBorder);

        RenderSystem.disableBlend();

        updateResizeCursor(mc, sw, sh, mx, my);
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
            titleBarAwaitingSecondClick = false;
            event.setCanceled(true);
            return;
        }

        DragMode r = hitTestResize(mx, my);
        if (r != DragMode.NONE) {
            titleBarAwaitingSecondClick = false;
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
            long now = System.nanoTime();
            if (titleBarAwaitingSecondClick && (now - titleBarFirstClickNs) <= TITLE_DOUBLE_CLICK_NS) {
                double dist = Math.hypot(mx - titleBarFirstClickMx, my - titleBarFirstClickMy);
                if (dist <= TITLE_DOUBLE_CLICK_MAX_DIST) {
                    toggleMaximizedLayout(sw, sh);
                    titleBarAwaitingSecondClick = false;
                    dragMode = DragMode.NONE;
                    event.setCanceled(true);
                    return;
                }
            }
            if (titleBarAwaitingSecondClick && (now - titleBarFirstClickNs) > TITLE_DOUBLE_CLICK_NS) {
                titleBarAwaitingSecondClick = false;
            }
            titleBarAwaitingSecondClick = true;
            titleBarFirstClickNs = now;
            titleBarFirstClickMx = mx;
            titleBarFirstClickMy = my;

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
            titleBarAwaitingSecondClick = false;
            applyGlfwCursor(mc, 0L);
        }
    }
}
