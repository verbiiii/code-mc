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
 * xos viewport on {@link ChatScreen}: launcher starts minimized — JNI does not run until Run; draggable
 * title bar with minimize / maximize / close; close stops the engine. Closing chat does not stop the
 * engine; it keeps simulating until close. While running and minimized, a green status dot appears
 * bottom-right.
 */
@Mod.EventBusSubscriber(modid = Minekov.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class XosViewportOverlay {

    private XosViewportOverlay() {}

    /** Softer mint / light neon — title bar, minus sign (border uses same hue, lower alpha). */
    private static final int NEON_RGB = 0x7DD87A;
    private static final int CLOSE_BTN_RGB = 0xE04040;
    private static final int STATUS_GREEN_RGB = 0x22CC44;
    private static final int INACTIVE_DOT_RGB = 0x888888;
    /** Muted bar when engine is running but panel is minimized */
    private static final int MUTED_LAUNCH_RGB = 0x6A7A6A;
    /** Bar when engine is stopped */
    private static final int INACTIVE_LAUNCH_RGB = 0x505050;

    /** Title chrome height (+10% vs prior 7px). */
    private static final int TITLE_BAR_H = 8;
    /** Minimize / maximize hit targets — centered vertically in the title bar */
    private static final int TITLE_BAR_BTN = 6;
    private static final int TITLE_BAR_BTN_GAP = 1;
    private static final int TITLE_BAR_PAD = 1;
    /** Top-left restore control when minimized (padding around label). */
    private static final int MINIMIZED_BTN_MARGIN = 8;
    private static final int MINIMIZED_BTN_PAD_X = 12;
    private static final int MINIMIZED_BTN_PAD_Y = 8;

    private static final String MINIMIZED_RESTORE_LABEL = "xos viewport";

    /** 1 logical px; drawn with reduced alpha so it reads ~30% thinner */
    private static final int BORDER_PX = 1;
    private static final float BORDER_ALPHA_MUL = 0.65f;
    /** Resize hit zones (~50% thinner than original 6/4 for easier corner vs edge grabs). */
    private static final int HANDLE_CORNER = 3;
    private static final int HANDLE_EDGE = 2;

    /** Default body size; ~20% larger than prior 0.26 → 0.312 */
    private static final float DEFAULT_BODY_FRAC = 0.312f;

    private static final int MIN_PANEL_W = 120;
    private static final int MIN_CONTENT_H = 72;

    /** At least this fraction of the panel must stay on-screen (10%). */
    private static final double MIN_VISIBLE_FRAC = 0.10;

    private static final float ALPHA_IDLE = 0.6f;
    private static final float ALPHA_HOVER = 0.8f;
    private static final float SMOOTH = 0.18f;

    private static float smoothedAlpha = ALPHA_IDLE;

    private static int panelX;
    private static int panelY;
    private static int panelW;
    /** Black viewport height (not including title bar). */
    private static int contentH;
    private static boolean layoutReady;
    /** Start with the panel collapsed so xos does not run until the user clicks Run. */
    private static boolean minimized = true;

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

    private static void clearTitleBarDoubleClickState() {
        titleBarAwaitingSecondClick = false;
    }

    private static boolean isResizeDragMode(DragMode m) {
        return m != DragMode.NONE && m != DragMode.MOVE;
    }

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
        if (!panelContains(mx, my, mc)) {
            applyGlfwCursor(mc, 0L);
            return;
        }
        if (minimized) {
            applyGlfwCursor(mc, cursorHand);
            return;
        }
        if (inMinimizeBtn(mx, my) || inMaximizeBtn(mx, my) || inCloseBtn(mx, my)) {
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

    /**
     * Maximize: one hollow square. Restore (when {@code maxed}): two overlapping hollow squares
     * (lower-left + upper-right) — same metaphor as Windows/Linux window controls.
     */
    private static void drawMaximizeGlyph(GuiGraphics g, int bx, int by, boolean maxed, int color) {
        if (!maxed) {
            int o = 1;
            int s = 3;
            g.fill(bx + o, by + o, bx + o + s, by + o + 1, color);
            g.fill(bx + o, by + o + s - 1, bx + o + s, by + o + s, color);
            g.fill(bx + o, by + o + 1, bx + o + 1, by + o + s - 1, color);
            g.fill(bx + o + s - 1, by + o + 1, bx + o + s, by + o + s - 1, color);
        } else {
            drawHollowSquare3(g, bx + 0, by + 1, color);
            drawHollowSquare3(g, bx + 2, by + 0, color);
        }
    }

    private static void drawHollowSquare3(GuiGraphics g, int bx, int by, int color) {
        g.fill(bx, by, bx + 3, by + 1, color);
        g.fill(bx, by + 2, bx + 3, by + 3, color);
        g.fill(bx, by + 1, bx + 1, by + 2, color);
        g.fill(bx + 2, by + 1, bx + 3, by + 2, color);
    }

    /** Simple 5×5-ish disk for status dots (GUI pixels). */
    private static void fillDisk5(GuiGraphics g, int cx, int cy, int argb) {
        g.fill(cx - 2, cy - 1, cx + 3, cy + 2, argb);
        g.fill(cx - 1, cy - 2, cx + 2, cy + 3, argb);
    }

    private static int rgbWithAlpha(float panelAlpha, int rgb) {
        int a = Math.min(255, Math.max(0, Math.round(panelAlpha * 255.0f)));
        return (a << 24) | (rgb & 0x00FFFFFF);
    }

    /** Two-stroke red X for the close button */
    private static void drawCloseGlyph(GuiGraphics g, int bx, int by, int argb) {
        int o = 1;
        for (int i = 0; i < 4; i++) {
            g.fill(bx + o + i, by + o + i, bx + o + i + 1, by + o + i + 1, argb);
            g.fill(bx + o + 3 - i, by + o + i, bx + o + 4 - i, by + o + i + 1, argb);
        }
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
     * height intersecting the GUI viewport. When minimized, only stored geometry is validated (panel is hidden).
     */
    private static void clampPartialOnScreen(int sw, int sh) {
        panelW = Math.max(MIN_PANEL_W, panelW);
        contentH = Math.max(MIN_CONTENT_H, contentH);
        int th = totalPanelH();

        if (minimized) {
            return;
        }

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
        clearTitleBarDoubleClickState();
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

    /** Rightmost = close; then maximize; then minimize */
    private static int closeBtnX() {
        return panelX + panelW - TITLE_BAR_PAD - TITLE_BAR_BTN;
    }

    private static int maximizeBtnX() {
        return closeBtnX() - TITLE_BAR_BTN_GAP - TITLE_BAR_BTN;
    }

    private static int minimizeBtnX() {
        return maximizeBtnX() - TITLE_BAR_BTN_GAP - TITLE_BAR_BTN;
    }

    private static int titleBarBtnY() {
        return panelY + (TITLE_BAR_H - TITLE_BAR_BTN) / 2;
    }

    private static boolean inMinimizeBtn(double mx, double my) {
        int bx = minimizeBtnX();
        int by = titleBarBtnY();
        return mx >= bx && mx < bx + TITLE_BAR_BTN && my >= by && my < by + TITLE_BAR_BTN;
    }

    private static boolean inMaximizeBtn(double mx, double my) {
        int bx = maximizeBtnX();
        int by = titleBarBtnY();
        return mx >= bx && mx < bx + TITLE_BAR_BTN && my >= by && my < by + TITLE_BAR_BTN;
    }

    private static boolean inCloseBtn(double mx, double my) {
        int bx = closeBtnX();
        int by = titleBarBtnY();
        return mx >= bx && mx < bx + TITLE_BAR_BTN && my >= by && my < by + TITLE_BAR_BTN;
    }

    private static boolean inTitleBarDragRegion(double mx, double my) {
        if (mx < panelX || mx >= panelX + panelW || my < panelY || my >= panelY + TITLE_BAR_H) {
            return false;
        }
        return !inMinimizeBtn(mx, my) && !inMaximizeBtn(mx, my) && !inCloseBtn(mx, my);
    }

    private static DragMode hitTestResize(double mx, double my) {
        if (minimized) {
            return DragMode.NONE;
        }
        int px = panelX;
        int py = panelY;
        int pw = panelW;
        int ph = totalPanelH();

        boolean onN = my >= py && my < py + HANDLE_EDGE;
        boolean onS = my >= py + ph - HANDLE_EDGE && my < py + ph;
        boolean onW = mx >= px && mx < px + HANDLE_EDGE;
        boolean onE = mx >= px + pw - HANDLE_EDGE && mx < px + pw;

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

    private static int minimizedRestoreBtnW(Minecraft mc) {
        return mc.font.width(MINIMIZED_RESTORE_LABEL) + 2 * MINIMIZED_BTN_PAD_X;
    }

    private static int minimizedRestoreBtnH(Minecraft mc) {
        return mc.font.lineHeight + 2 * MINIMIZED_BTN_PAD_Y;
    }

    private static boolean inMinimizedRestoreBtn(double mx, double my, Minecraft mc) {
        int x = MINIMIZED_BTN_MARGIN;
        int y = MINIMIZED_BTN_MARGIN;
        int w = minimizedRestoreBtnW(mc);
        int h = minimizedRestoreBtnH(mc);
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Content area below the title bar (xos framebuffer). */
    private static boolean inViewportBody(double mx, double my) {
        if (minimized) {
            return false;
        }
        return mx >= panelX
                && mx < panelX + panelW
                && my >= panelY + TITLE_BAR_H
                && my < panelY + TITLE_BAR_H + contentH;
    }

    private static boolean panelContains(double mx, double my, Minecraft mc) {
        if (minimized) {
            return inMinimizedRestoreBtn(mx, my, mc);
        }
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

        switch (dragMode) {
            case MOVE -> {
                panelX = (int) Math.round(moveGrabPanelX + (mx - moveGrabMouseX));
                panelY = (int) Math.round(moveGrabPanelY + (my - moveGrabMouseY));
            }
            case RESIZE_N -> {
                int bottom = anchorPanelY + TITLE_BAR_H + anchorContentH;
                int newY = (int) Math.round(Mth.clamp(my, 0, bottom - TITLE_BAR_H - MIN_CONTENT_H));
                panelY = newY;
                contentH = bottom - TITLE_BAR_H - panelY;
            }
            case RESIZE_S -> {
                contentH = (int) Math.round(Mth.clamp(
                        my - anchorPanelY - TITLE_BAR_H,
                        MIN_CONTENT_H,
                        sh));
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
                int bottom = anchorPanelY + TITLE_BAR_H + anchorContentH;
                int newY = (int) Math.round(Mth.clamp(my, 0, bottom - TITLE_BAR_H - MIN_CONTENT_H));
                panelY = newY;
                contentH = bottom - TITLE_BAR_H - panelY;
                panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw * 2));
            }
            case RESIZE_NW -> {
                int bottom = anchorPanelY + TITLE_BAR_H + anchorContentH;
                int right = anchorPanelX + anchorPanelW;
                int newY = (int) Math.round(Mth.clamp(my, 0, bottom - TITLE_BAR_H - MIN_CONTENT_H));
                int newX = (int) Math.round(Mth.clamp(mx, -sw, right - MIN_PANEL_W));
                panelY = newY;
                panelX = newX;
                contentH = bottom - TITLE_BAR_H - panelY;
                panelW = right - newX;
            }
            case RESIZE_SE -> {
                panelW = (int) Math.round(Mth.clamp(mx - anchorPanelX, MIN_PANEL_W, sw * 2));
                contentH = (int) Math.round(Mth.clamp(
                        my - anchorPanelY - TITLE_BAR_H,
                        MIN_CONTENT_H,
                        sh));
            }
            case RESIZE_SW -> {
                int right = anchorPanelX + anchorPanelW;
                int newX = (int) Math.round(Mth.clamp(mx, -sw, right - MIN_PANEL_W));
                panelX = newX;
                panelW = right - newX;
                contentH = (int) Math.round(Mth.clamp(
                        my - anchorPanelY - TITLE_BAR_H,
                        MIN_CONTENT_H,
                        sh));
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

        XosViewportRuntime.pumpFrame(mc, panelW, contentH);

        double mx = scaledMouseX(mc, sw);
        double my = scaledMouseY(mc, sh);

        boolean hover = panelContains(mx, my, mc);
        float target = hover ? ALPHA_HOVER : ALPHA_IDLE;
        smoothedAlpha += (target - smoothedAlpha) * SMOOTH;

        float drawA = smoothedAlpha;
        int neon = neonArgb(drawA);
        int neonBorder = neonBorderArgb(drawA);
        int blk = blackArgb(drawA);
        int titleTextColor =
                (Math.min(255, Math.max(0, Math.round(drawA * 255.0f))) << 24) | 0x00202020;

        GuiGraphics g = event.getGuiGraphics();

        if (minimized) {
            int bx = MINIMIZED_BTN_MARGIN;
            int by = MINIMIZED_BTN_MARGIN;
            int bw = minimizedRestoreBtnW(mc);
            int bh = minimizedRestoreBtnH(mc);

            boolean session = XosViewportRuntime.isRunSession();
            int launchRgb = session ? MUTED_LAUNCH_RGB : INACTIVE_LAUNCH_RGB;
            int launchFill = rgbWithAlpha(drawA, launchRgb);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();

            g.fill(bx, by, bx + bw, by + bh, launchFill);
            if (hover) {
                int hoverA = Math.min(255, Math.round(drawA * 0.22f * 255.0f));
                g.fill(bx, by, bx + bw, by + bh, (hoverA << 24));
            }
            int dotCx = bx + MINIMIZED_BTN_PAD_X - 6;
            int dotCy = by + bh / 2;
            int inactiveDot = rgbWithAlpha(drawA, INACTIVE_DOT_RGB);
            if (!session) {
                fillDisk5(g, dotCx, dotCy, inactiveDot);
            }
            int tx = bx + MINIMIZED_BTN_PAD_X;
            int ty = by + MINIMIZED_BTN_PAD_Y;
            g.drawString(mc.font, MINIMIZED_RESTORE_LABEL, tx, ty, titleTextColor, false);

            int borderCol = session ? neonBorderArgb(drawA * 0.75f) : neonBorderArgb(drawA * 0.5f);
            g.fill(bx, by, bx + bw, by + BORDER_PX, borderCol);
            g.fill(bx, by + bh - BORDER_PX, bx + bw, by + bh, borderCol);
            g.fill(bx, by, bx + BORDER_PX, by + bh, borderCol);
            g.fill(bx + bw - BORDER_PX, by, bx + bw, by + bh, borderCol);

            if (session) {
                int gx = sw - 12;
                int gy = sh - 12;
                fillDisk5(g, gx, gy, rgbWithAlpha(drawA, STATUS_GREEN_RGB));
            }

            RenderSystem.disableBlend();

            updateResizeCursor(mc, sw, sh, mx, my);
            return;
        }

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

        int titlePad = BORDER_PX + 1;
        int textY = oy + Math.max(0, (th - mc.font.lineHeight) / 2);
        g.drawString(mc.font, "xos viewport", ox + titlePad, textY, titleTextColor, false);

        int btnY = titleBarBtnY();
        int minBx = minimizeBtnX();
        int maxBx = maximizeBtnX();
        int clsBx = closeBtnX();
        boolean hovMin = inMinimizeBtn(mx, my);
        boolean hovMax = inMaximizeBtn(mx, my);
        boolean hovCls = inCloseBtn(mx, my);

        // Minimize: optional faded hover, then a thin black dash (no square)
        if (hovMin) {
            int hoverA = Math.min(255, Math.round(drawA * 0.22f * 255.0f));
            g.fill(minBx, btnY, minBx + TITLE_BAR_BTN, btnY + TITLE_BAR_BTN, (hoverA << 24));
        }
        int dashW = 2;
        int cxMin = minBx + TITLE_BAR_BTN / 2;
        int dashY = btnY + TITLE_BAR_BTN / 2;
        g.fill(cxMin - dashW / 2, dashY, cxMin - dashW / 2 + dashW, dashY + 1, blackArgb(drawA));

        // Maximize / restore: optional faded hover, square glyph in neon
        if (hovMax) {
            int hoverA = Math.min(255, Math.round(drawA * 0.22f * 255.0f));
            g.fill(maxBx, btnY, maxBx + TITLE_BAR_BTN, btnY + TITLE_BAR_BTN, (hoverA << 24));
        }
        drawMaximizeGlyph(g, maxBx, btnY, maximized, blackArgb(drawA));

        if (hovCls) {
            int hoverA = Math.min(255, Math.round(drawA * 0.22f * 255.0f));
            g.fill(clsBx, btnY, clsBx + TITLE_BAR_BTN, btnY + TITLE_BAR_BTN, (hoverA << 24));
        }
        drawCloseGlyph(g, clsBx, btnY, rgbWithAlpha(drawA, CLOSE_BTN_RGB));

        int bodyTop = oy + th;
        if (bodyH > 0) {
            XosViewportRuntime.syncPointer(mc, mx, my, ox, bodyTop, ow, bodyH);
            if (!XosViewportRuntime.blitViewport(g, ox, bodyTop, ow, bodyH)) {
                g.fill(ox, bodyTop, ox + ow, bodyTop + bodyH, blk);
            }
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

        Minecraft mc = Minecraft.getInstance();
        double mx = scaledMouseX(mc, sw);
        double my = scaledMouseY(mc, sh);

        if (minimized) {
            if (inMinimizedRestoreBtn(mx, my, mc)) {
                if (!XosViewportRuntime.isRunSession()) {
                    XosViewportRuntime.setRunSession(true);
                }
                minimized = false;
                clampPartialOnScreen(sw, sh);
                dragMode = DragMode.NONE;
                clearTitleBarDoubleClickState();
                event.setCanceled(true);
            }
            return;
        }

        if (!panelContains(mx, my, mc)) {
            return;
        }

        if (inCloseBtn(mx, my)) {
            XosViewportRuntime.setRunSession(false);
            minimized = true;
            clampPartialOnScreen(sw, sh);
            dragMode = DragMode.NONE;
            clearTitleBarDoubleClickState();
            event.setCanceled(true);
            return;
        }

        if (inMinimizeBtn(mx, my)) {
            minimized = !minimized;
            clampPartialOnScreen(sw, sh);
            dragMode = DragMode.NONE;
            clearTitleBarDoubleClickState();
            event.setCanceled(true);
            return;
        }

        if (inMaximizeBtn(mx, my)) {
            toggleMaximizedLayout(sw, sh);
            dragMode = DragMode.NONE;
            event.setCanceled(true);
            return;
        }

        DragMode r = hitTestResize(mx, my);
        if (r != DragMode.NONE) {
            clearTitleBarDoubleClickState();
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

        if (inViewportBody(mx, my)) {
            int bodyTop = panelY + TITLE_BAR_H;
            XosViewportRuntime.syncPointer(mc, mx, my, panelX, bodyTop, panelW, contentH);
            XosViewportRuntime.onMouseDownInBody();
            event.setCanceled(true);
            return;
        }

        if (inTitleBarDragRegion(mx, my)) {
            long now = System.nanoTime();
            if (titleBarAwaitingSecondClick && (now - titleBarFirstClickNs) <= TITLE_DOUBLE_CLICK_NS) {
                double dist = Math.hypot(mx - titleBarFirstClickMx, my - titleBarFirstClickMy);
                if (dist <= TITLE_DOUBLE_CLICK_MAX_DIST) {
                    toggleMaximizedLayout(sw, sh);
                    dragMode = DragMode.NONE;
                    event.setCanceled(true);
                    return;
                }
            }
            if (titleBarAwaitingSecondClick && (now - titleBarFirstClickNs) > TITLE_DOUBLE_CLICK_NS) {
                clearTitleBarDoubleClickState();
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
        XosViewportRuntime.onMouseUpLeft();
        DragMode prev = dragMode;
        if (maximized) {
            if (prev == DragMode.MOVE) {
                if (panelX != moveGrabPanelX || panelY != moveGrabPanelY) {
                    restorePanelX = panelX;
                    restorePanelY = panelY;
                    restorePanelW = panelW;
                    restoreContentH = contentH;
                    restoreMinimized = minimized;
                    clearTitleBarDoubleClickState();
                }
            } else if (isResizeDragMode(prev)) {
                restorePanelX = panelX;
                restorePanelY = panelY;
                restorePanelW = panelW;
                restoreContentH = contentH;
                restoreMinimized = minimized;
                clearTitleBarDoubleClickState();
            }
        } else {
            if (prev == DragMode.MOVE) {
                if (panelX != moveGrabPanelX || panelY != moveGrabPanelY) {
                    clearTitleBarDoubleClickState();
                }
            } else if (isResizeDragMode(prev)) {
                clearTitleBarDoubleClickState();
            }
        }
        dragMode = DragMode.NONE;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        boolean chatOpen = mc.screen instanceof ChatScreen;

        if (!chatOpen) {
            smoothedAlpha = ALPHA_IDLE;
            dragMode = DragMode.NONE;
            clearTitleBarDoubleClickState();
            applyGlfwCursor(mc, 0L);
        }
    }

    /**
     * While chat is closed, advance the JNI engine every frame so the simulation matches in-game
     * framerate (chat open uses {@link ScreenEvent.Render.Post} instead).
     */
    @SubscribeEvent
    public static void onRenderTickBackgroundPump(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ChatScreen) {
            return;
        }
        if (!XosViewportRuntime.isRunSession() || !layoutReady || panelW < 1 || contentH < 1) {
            return;
        }
        XosViewportRuntime.pumpFrame(mc, panelW, contentH);
    }
}
