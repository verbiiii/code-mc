package com.verbii.minekov.client;

import ai.xlate.xos.XosNative;

import com.mojang.blaze3d.platform.NativeImage;
import com.verbii.minekov.Minekov;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.lwjgl.system.MemoryUtil;

/**
 * Runs the xos engine (ball app via JNI) when {@link #setRunSession(boolean) run session} is on;
 * uploads each packed frame into a {@link DynamicTexture}. Native code premultiplies and packs
 * pixels for {@link NativeImage#setPixelRGBA}; Java does one int per pixel (not four byte gets +
 * premultiply). Pump with {@link #pumpFrame}; blit with {@link #blitViewport} when the body is visible.
 * <p>
 * <strong>Threading:</strong> The Rust host is {@code thread_local} and must stay on Minecraft’s
 * client thread, so each {@link #pumpFrame} adds work to the same thread that draws the game. That
 * tends to couple Minecraft FPS to xos update cost. Use {@link #setMaxPumpsPerSecond(int)} to cap how
 * often tick+upload run so Minecraft can reach higher FPS while the panel shows slightly staler frames.
 * Fully decoupling (1000+ MC FPS while xos simulates flat-out on another core) needs a larger change
 * (e.g. a {@code Send}-safe engine + mutex, or a separate process).
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
    private static final List<String> STARTER_SCRIPT_NAMES = List.of("balls_many.py", "demo_mod.py");

    private static boolean libraryTried;
    private static boolean libraryOk;
    private static boolean engineRunning;

    /** Forge 1.20.1 has no reliable client-stop event on the classpath; hook ensures Rust/JNI teardown on JVM exit. */
    private static volatile boolean jvmExitHookRegistered;

    /** User chose Run; cleared on Close or when leaving chat. */
    private static boolean runSession;

    private static int texW = -1;
    private static int texH = -1;

    private static NativeImage nativeImage;
    private static DynamicTexture dynamicTexture;

    /**
     * Reuse one direct buffer view from JNI — {@link XosNative#getFrameBuffer()} allocates a new
     * wrapper each call; caching avoids per-frame JNI allocation. Refresh after init/resize only.
     */
    private static ByteBuffer cachedPackedFrameBuffer;

    /**
     * Mojang 1.20.1 {@link NativeImage} stores pixels at native {@code long pointer} (see mappings.dev).
     * Used for one {@link MemoryUtil#memCopy} instead of millions of {@code setPixelRGBA} calls.
     */
    private static Field nativeImagePointerField;

    static {
        try {
            Field f = NativeImage.class.getDeclaredField("pointer");
            f.setAccessible(true);
            nativeImagePointerField = f;
        } catch (ReflectiveOperationException e) {
            nativeImagePointerField = null;
            LOGGER.warn("NativeImage.pointer not accessible; xos viewport copy falls back to slow path", e);
        }
    }

    /** Set each frame before {@link #pumpFrame} (chat) or background pump (not hovered). */
    private static boolean panelHovered;

    /**
     * Caps how often {@link #pumpFrame} runs the native {@link XosNative#tick} + texture upload.
     * <ul>
     *   <li>{@code 0} (default): every Minecraft frame that calls {@link #pumpFrame} — can cap Minecraft
     *       FPS because JNI work runs on the client thread (see class javadoc).</li>
     *   <li>{@code 120}–{@code 240}: xos updates at most that many times per second; Minecraft can often
     *       reach much higher FPS since most frames skip the heavy pump. The viewport still blits the
     *       last uploaded frame.</li>
     * </ul>
     * True “full speed xos + uncapped MC FPS” in parallel is not possible on one thread; use this knob
     * to favour Minecraft, or plan a future Rust change (off-thread host / subprocess).
     */
    private static volatile int maxPumpsPerSecond;

    private static long lastPumpNanos;
    private static boolean hostBindingsRegistered;

    private XosViewportRuntime() {}

    /**
     * @param max 0 = no limit (pump every caller frame). Otherwise clamped to 1–2000 pumps/s.
     */
    public static void setMaxPumpsPerSecond(int max) {
        maxPumpsPerSecond = Math.max(0, Math.min(max, 2000));
    }

    public static int getMaxPumpsPerSecond() {
        return maxPumpsPerSecond;
    }

    /** Whether the mouse is over the xos panel (minimized strip or full window). Drives viewport α 60%/80%. */
    public static void setPanelHovered(boolean hovered) {
        panelHovered = hovered;
    }

    public static boolean isRunSession() {
        return runSession;
    }

    /** Native library loaded and {@link XosNative#init} completed (e.g. after {@link #prewarmEngine}). */
    public static boolean isEngineReady() {
        return libraryOk && engineRunning;
    }

    /**
     * Starts or pauses the xos simulation. Pausing does <strong>not</strong> tear down the native
     * engine (library stays loaded, {@link XosNative#init} state kept) so Run resumes instantly.
     * Full GPU + native release: {@link #disposeEngine()} (optional; JVM exit also calls {@link XosNative#shutdown()} via a hook).
     */
    public static void setRunSession(boolean active) {
        runSession = active;
    }

    private static void tryLoadLibrary() {
        if (libraryTried) {
            return;
        }
        libraryTried = true;
        try {
            XosNative.initLibraryFromPath();
            libraryOk = true;
            registerJvmExitHook();
        } catch (Throwable t) {
            libraryOk = false;
            LOGGER.warn("xos_java not loaded (java.library.path); viewport stays black", t);
        }
    }

    private static void registerJvmExitHook() {
        if (jvmExitHookRegistered) {
            return;
        }
        jvmExitHookRegistered = true;
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    try {
                                        XosNative.shutdown();
                                    } catch (Throwable ignored) {
                                    }
                                },
                                "xos-java-shutdown"));
    }

    private static boolean prewarmAttempted;

    /**
     * Allocates the native engine + GPU texture early (e.g. first client tick) so the first Run only
     * starts ticking, not loading DLLs or running {@link XosNative#init}.
     */
    public static void prewarmEngine(Minecraft mc) {
        if (prewarmAttempted || mc == null || mc.getWindow() == null) {
            return;
        }
        prewarmAttempted = true;
        tryLoadLibrary();
        if (!libraryOk) {
            return;
        }
        int gw = mc.getWindow().getGuiScaledWidth();
        int gh = mc.getWindow().getGuiScaledHeight();
        int fbW = framebufferWidth(mc, gw);
        int fbH = framebufferHeight(mc, gh);
        ensureEngineAndTexture(mc, fbW, fbH);
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

        boolean mustPump =
                !engineRunning || fbW != texW || fbH != texH;
        ensureEngineAndTexture(mc, fbW, fbH);

        int cap = maxPumpsPerSecond;
        if (!mustPump && cap > 0) {
            long minNs = 1_000_000_000L / (long) cap;
            long now = System.nanoTime();
            if (lastPumpNanos != 0L && now - lastPumpNanos < minNs) {
                return;
            }
            lastPumpNanos = now;
        } else if (cap > 0) {
            lastPumpNanos = System.nanoTime();
        }

        XosNative.setMinecraftViewportAlpha(panelHovered ? VIEWPORT_ALPHA_HOVER : VIEWPORT_ALPHA_IDLE);
        XosNative.tick();

        ByteBuffer buf = cachedPackedFrameBuffer;
        if (buf == null || nativeImage == null) {
            return;
        }

        int need = fbW * fbH * 4;
        if (buf.capacity() < need) {
            return;
        }

        copyPackedToNativeImage(buf, fbW, fbH);
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

    /**
     * Forward Unicode input to the native app when chat is routing keys to xos (hover + run session).
     */
    public static void sendKeyCharToEngine(int codepoint) {
        if (!runSession) {
            return;
        }
        tryLoadLibrary();
        if (!libraryOk || !engineRunning) {
            return;
        }
        XosNative.onKeyChar(codepoint);
    }

    /** F3 → global FPS overlay toggle (matches desktop xos; not a text character). */
    public static void sendF3ToEngine() {
        if (!runSession) {
            return;
        }
        tryLoadLibrary();
        if (!libraryOk || !engineRunning) {
            return;
        }
        XosNative.onF3();
    }

    /**
     * Wheel / trackpad scroll into the native app (matches framebuffer scale like {@link #syncPointer}).
     * Minecraft typically reports ~±1.0 per notch; we scale into engine space so momentum feels right.
     */
    public static void sendScrollToEngine(Minecraft mc, double deltaX, double deltaY) {
        if (!runSession) {
            return;
        }
        tryLoadLibrary();
        if (!libraryOk || !engineRunning) {
            return;
        }
        float s = (float) effectiveGuiScale(mc);
        float notchToEngine = 28f;
        XosNative.onScroll(
                (float) (deltaX * s * notchToEngine),
                (float) (deltaY * s * notchToEngine));
    }

    /**
     * Full teardown: native shutdown, texture release. Panel close only pauses ({@link #setRunSession(boolean)});
     * use this if you need to free VRAM without exiting the game. JVM shutdown also runs {@link XosNative#shutdown()}.
     */
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
        cachedPackedFrameBuffer = null;
        texW = -1;
        texH = -1;
        engineRunning = false;
        try {
            XosNative.shutdown();
        } catch (Throwable ignored) {
        }
        panelHovered = false;
        prewarmAttempted = false;
        lastPumpNanos = 0L;
    }

    private static Path resolvePlayerCoderScriptsDirectory(Minecraft mc) {
        if (mc == null || mc.player == null || mc.getSingleplayerServer() == null) {
            return null;
        }
        Path worldRoot = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
        if (worldRoot == null) {
            return null;
        }
        String playerUuid = mc.player.getUUID().toString();
        return worldRoot.resolve("xos").resolve(playerUuid);
    }

    private static void ensureStarterScript(Path scriptsDir, String scriptName) {
        Path starterFile = scriptsDir.resolve(scriptName);
        if (Files.exists(starterFile)) {
            return;
        }
        String resourcePath = "/xos/examples/" + scriptName;
        try (InputStream resourceStream = XosViewportRuntime.class.getResourceAsStream(resourcePath)) {
            if (resourceStream != null) {
                Files.copy(resourceStream, starterFile, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to copy xos starter script from classpath resource {}", resourcePath, e);
        }

        Path source = Path.of("src", "xos", "examples", scriptName).toAbsolutePath().normalize();
        try {
            if (Files.exists(source)) {
                Files.copy(source, starterFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                LOGGER.warn("xos starter script '{}' not found in resource or dev path ({})", scriptName, source);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to copy xos starter script to {}", starterFile, e);
        }
    }

    private static void ensureStarterScripts(Path scriptsDir) {
        for (String scriptName : STARTER_SCRIPT_NAMES) {
            ensureStarterScript(scriptsDir, scriptName);
        }
    }

    private static void runOnClientThreadSync(Minecraft mc, Runnable action) throws Exception {
        if (mc == null) {
            return;
        }
        if (mc.isSameThread()) {
            action.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        mc.execute(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                errorRef.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(3, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timed out waiting for Minecraft client thread.");
        }
        Throwable t = errorRef.get();
        if (t != null) {
            throw new RuntimeException(t);
        }
    }

    private static String invokeHostBinding(Minecraft mc, String moduleName, String functionName, String arg0) {
        if ("mc".equals(moduleName) && "chat".equals(functionName)) {
            String message = arg0 != null ? arg0 : "";
            try {
                runOnClientThreadSync(mc, () -> {
                    if (mc.player != null && !message.isBlank()) {
                        mc.player.displayClientMessage(Component.literal(message), false);
                    }
                });
                return null;
            } catch (Exception e) {
                throw new RuntimeException("mc.chat failed: " + e.getMessage(), e);
            }
        }
        throw new IllegalArgumentException("Unknown host binding: " + moduleName + "." + functionName);
    }

    private static void configureHostBindings(Minecraft mc) {
        if (hostBindingsRegistered) {
            return;
        }
        XosNative.setHostBindingCallback(
                (moduleName, functionName, arg0) ->
                        invokeHostBinding(mc, moduleName, functionName, arg0));
        XosNative.clearHostPythonModules();
        XosNative.registerHostPythonModule("mc", new String[] {"chat"});
        hostBindingsRegistered = true;
    }

    private static boolean configureCoderScriptsDirectory(Minecraft mc) {
        Path scriptsDir = resolvePlayerCoderScriptsDirectory(mc);
        if (scriptsDir == null) {
            return false;
        }
        try {
            Files.createDirectories(scriptsDir);
            ensureStarterScripts(scriptsDir);
            XosNative.setCoderScriptsDirectory(scriptsDir.toAbsolutePath().normalize().toString());
            configureHostBindings(mc);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("Failed to configure xos coder scripts directory", t);
            return false;
        }
    }

    private static void ensureEngineAndTexture(Minecraft mc, int fbW, int fbH) {
        if (!engineRunning) {
            // Delay first native init until we have a concrete world/player scripts directory.
            if (!configureCoderScriptsDirectory(mc)) {
                return;
            }
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
        refreshCachedFrameBuffer();
    }

    /** Must run after {@link XosNative#init}/{@link XosNative#resize} reallocates the native upload buffer. */
    private static void refreshCachedFrameBuffer() {
        cachedPackedFrameBuffer = XosNative.getFrameBuffer();
        if (cachedPackedFrameBuffer != null) {
            cachedPackedFrameBuffer.order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    /**
     * Buffer from JNI is packed in native code (premultiply + uniform viewport alpha + ABGR int as LE
     * bytes). One {@link IntBuffer} read per pixel — much cheaper than the old per-byte RGBA path.
     * <p>
     * Fast path: one native memcpy from the JNI direct buffer into {@link NativeImage}'s pixel pointer.
     * Fallback: int-at-a-time {@code setPixelRGBA} (very slow on large viewports).
     * <p>
     * Further gains: async PBO {@code glTexSubImage2D}, or upload straight from Rust with a JNI GL
     * hook (bypasses {@link DynamicTexture} but couples tightly to render thread + GL state).
     */
    private static void copyPackedToNativeImage(ByteBuffer buf, int w, int h) {
        int nbytes = w * h * 4;
        buf.clear();
        if (nativeImagePointerField != null && buf.isDirect()) {
            try {
                long dst = nativeImagePointerField.getLong(nativeImage);
                if (dst != 0L) {
                    long src = MemoryUtil.memAddress(buf);
                    MemoryUtil.memCopy(src, dst, nbytes);
                    return;
                }
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
        }
        buf.order(ByteOrder.LITTLE_ENDIAN);
        IntBuffer ib = buf.asIntBuffer();
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                nativeImage.setPixelRGBA(x, y, ib.get(row + x));
            }
        }
    }
}
