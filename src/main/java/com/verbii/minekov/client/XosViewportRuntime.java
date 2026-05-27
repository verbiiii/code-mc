package com.verbii.minekov.client;

import ai.xlate.xos.XosNative;

import com.mojang.blaze3d.platform.NativeImage;
import com.verbii.minekov.Minekov;
import com.verbii.minekov.entities.RLOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final List<String> STARTER_SCRIPT_NAMES =
            List.of(
                    "balls_many.py",
                    "demo_mod.py",
                    "agent_controller.py",
                    "look_at_me.py",
                    "walk_towards_me.py",
                    "jump.py",
                    "agent_jump.py");

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
    /**
     * Rotation lock requested from mc Python bindings. Values are [yaw, pitch] and are re-applied each
     * frame so Minecraft AI/network updates do not drift agents off target.
     */
    private static final Map<String, float[]> forcedAgentRotations = new HashMap<>();

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
        if (!active && runSession) {
            stopActiveExecution();
        }
        runSession = active;
    }

    /** Stops currently running Coder execution(s) inside xos, if engine is initialized. */
    public static void stopActiveExecution() {
        if (!libraryOk || !engineRunning) {
            return;
        }
        try {
            XosNative.onStopExecution();
        } catch (Throwable ignored) {
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

        applyForcedAgentRotations(mc);
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
        stopActiveExecution();
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

    private static String encodePosition(double x, double y, double z) {
        return String.format(Locale.US, "%.6f,%.6f,%.6f", x, y, z);
    }

    private static String encodeRotation(float yaw, float pitch) {
        return String.format(Locale.US, "%.6f,%.6f", (double) yaw, (double) pitch);
    }

    private static String encodeVelocity(Vec3 v) {
        return encodePosition(v.x, v.y, v.z);
    }

    private static double[] parseTriple(String raw, double fallbackX, double fallbackY, double fallbackZ) {
        if (raw == null || raw.isBlank()) {
            return new double[] {fallbackX, fallbackY, fallbackZ};
        }
        String[] parts = raw.split(",");
        if (parts.length < 3) {
            return new double[] {fallbackX, fallbackY, fallbackZ};
        }
        try {
            return new double[] {
                    Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()),
                    Double.parseDouble(parts[2].trim())
            };
        } catch (Exception ignored) {
            return new double[] {fallbackX, fallbackY, fallbackZ};
        }
    }

    private static float[] parsePair(String raw, float fallbackA, float fallbackB) {
        if (raw == null || raw.isBlank()) {
            return new float[] {fallbackA, fallbackB};
        }
        String[] parts = raw.split(",");
        if (parts.length < 2) {
            return new float[] {fallbackA, fallbackB};
        }
        try {
            return new float[] {
                    Float.parseFloat(parts[0].trim()),
                    Float.parseFloat(parts[1].trim())
            };
        } catch (Exception ignored) {
            return new float[] {fallbackA, fallbackB};
        }
    }

    private static double movementImpulseFor(Entity entity) {
        if (entity instanceof LivingEntity living) {
            var attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
            if (attr != null) {
                return attr.getValue();
            }
        }
        return 0.1;
    }

    private static Vec3 forwardFromRotation(float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double x = -Math.sin(yaw) * Math.cos(pitch);
        double y = -Math.sin(pitch);
        double z = Math.cos(yaw) * Math.cos(pitch);
        return new Vec3(x, y, z).normalize();
    }

    private static Vec3 rightFromYaw(float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        // Horizontal right vector from yaw only, stable near vertical pitch.
        return new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw)).normalize();
    }

    private static Vec3 upFromBasis(Vec3 right, Vec3 forward) {
        Vec3 up = right.cross(forward);
        if (up.lengthSqr() < 1.0e-8) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        return up.normalize();
    }

    private static Vec3 worldToLookingVelocity(Vec3 worldVel, float yawDeg, float pitchDeg) {
        Vec3 forward = forwardFromRotation(yawDeg, pitchDeg);
        Vec3 right = rightFromYaw(yawDeg);
        Vec3 up = upFromBasis(right, forward);
        return new Vec3(worldVel.dot(right), worldVel.dot(up), worldVel.dot(forward));
    }

    private static Vec3 lookingToWorldVelocity(Vec3 localVel, float yawDeg, float pitchDeg) {
        Vec3 forward = forwardFromRotation(yawDeg, pitchDeg);
        Vec3 right = rightFromYaw(yawDeg);
        Vec3 up = upFromBasis(right, forward);
        return right.scale(localVel.x).add(up.scale(localVel.y)).add(forward.scale(localVel.z));
    }

    private static Entity resolveServerMirrorEntity(Minecraft mc, Entity clientEntity) {
        if (mc == null || mc.level == null || clientEntity == null) {
            return null;
        }
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return null;
        }
        var serverLevel = server.getLevel(mc.level.dimension());
        if (serverLevel == null) {
            return null;
        }
        return serverLevel.getEntity(clientEntity.getUUID());
    }

    private static void setEntityVelocityWithServerMirror(Minecraft mc, Entity entity, Vec3 velocity) {
        if (entity == null || velocity == null) {
            return;
        }
        entity.setDeltaMovement(velocity);
        entity.hurtMarked = true;
        Entity mirror = resolveServerMirrorEntity(mc, entity);
        if (mirror != null && mirror != entity) {
            mirror.setDeltaMovement(velocity);
            mirror.hurtMarked = true;
        }
    }

    private static void applyMovementImpulse(Entity entity, double forward, double strafe) {
        if (entity instanceof RLOperator rl) {
            double speed = movementImpulseFor(rl);
            if (forward > 0.0) {
                rl.moveTowards(180.0f, (float) speed);
                return;
            }
            if (forward < 0.0) {
                rl.moveTowards(0.0f, (float) speed);
                return;
            }
            if (strafe < 0.0) {
                rl.moveTowards(-90.0f, (float) speed);
                return;
            }
            if (strafe > 0.0) {
                rl.moveTowards(90.0f, (float) speed);
                return;
            }
        }

        double speed = movementImpulseFor(entity);
        double yawRad = Math.toRadians(entity.getYRot());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double lx = -fz;
        double lz = fx;

        Vec3 cur = entity.getDeltaMovement();
        double vx = cur.x + (fx * forward + lx * strafe) * speed;
        double vz = cur.z + (fz * forward + lz * strafe) * speed;
        entity.setDeltaMovement(vx, cur.y, vz);
        entity.hurtMarked = true;
    }

    private static void applyLookDelta(Entity entity, float pitchDelta, float yawDelta) {
        float nextPitch = entity.getXRot() + pitchDelta;
        float nextYaw = entity.getYRot() + yawDelta;
        entity.setXRot(nextPitch);
        entity.setYRot(nextYaw);
        if (entity instanceof LivingEntity living) {
            living.setYHeadRot(nextYaw);
            living.setYBodyRot(nextYaw);
        }
    }

    private static void applyEntityActionWithServerMirror(
            Minecraft mc, Entity entity, String action, String payload) {
        applyEntityAction(entity, action, payload);
        Entity mirror = resolveServerMirrorEntity(mc, entity);
        if (mirror != null && mirror != entity) {
            applyEntityAction(mirror, action, payload);
        }
    }

    private static void applyEntityAction(Entity entity, String action, String payload) {
        switch (action) {
            case "w" -> applyMovementImpulse(entity, 1.0, 0.0);
            case "s" -> applyMovementImpulse(entity, -1.0, 0.0);
            case "a" -> applyMovementImpulse(entity, 0.0, -1.0);
            case "d" -> applyMovementImpulse(entity, 0.0, 1.0);
            case "jump" -> {
                if (entity instanceof RLOperator rl) {
                    rl.jumpEntity();
                } else {
                    Vec3 cur = entity.getDeltaMovement();
                    entity.setDeltaMovement(cur.x, Math.max(cur.y, 0.42), cur.z);
                    entity.hurtMarked = true;
                }
            }
            case "look" -> {
                float[] delta = parsePair(payload, 0.0f, 0.0f);
                // API order is look(pitch, yaw)
                applyLookDelta(entity, delta[0], delta[1]);
            }
            default -> {}
        }
    }

    private static RLOperator findAgentById(Minecraft mc, String id) {
        if (mc == null || mc.level == null || id == null || id.isBlank()) {
            return null;
        }
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof RLOperator operator && id.equals(operator.getStringUUID())) {
                return operator;
            }
        }
        return null;
    }

    private static void applyAgentRotation(RLOperator agent, float yaw, float pitch) {
        agent.setYRot(yaw);
        agent.setXRot(pitch);
        agent.setYHeadRot(yaw);
        agent.setYBodyRot(yaw);
    }

    private static void applyForcedAgentRotations(Minecraft mc) {
        if (forcedAgentRotations.isEmpty()) {
            return;
        }
        for (RLOperator agent : collectAgents(mc)) {
            float[] rot = forcedAgentRotations.get(agent.getStringUUID());
            if (rot == null || rot.length < 2) {
                continue;
            }
            applyAgentRotation(agent, rot[0], rot[1]);
        }
    }

    /** Keep locked rotations applied even when no script is currently running. */
    public static void maintainForcedAgentRotations(Minecraft mc) {
        applyForcedAgentRotations(mc);
    }

    /** Clear all rotation locks (used on world unload/disconnect). */
    public static void clearForcedAgentRotations() {
        forcedAgentRotations.clear();
    }

    private static List<RLOperator> collectAgents(Minecraft mc) {
        java.util.ArrayList<RLOperator> out = new java.util.ArrayList<>();
        if (mc == null || mc.level == null) {
            return out;
        }
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof RLOperator operator) {
                out.add(operator);
            }
        }
        return out;
    }

    private static String encodeAgentPositions(Minecraft mc) {
        StringBuilder out = new StringBuilder();
        for (RLOperator agent : collectAgents(mc)) {
            if (!out.isEmpty()) {
                out.append("|");
            }
            out.append(encodePosition(agent.getX(), agent.getY(), agent.getZ()));
        }
        return out.toString();
    }

    private static String encodeAgentRotations(Minecraft mc) {
        StringBuilder out = new StringBuilder();
        for (RLOperator agent : collectAgents(mc)) {
            if (!out.isEmpty()) {
                out.append("|");
            }
            out.append(encodeRotation(agent.getYRot(), agent.getXRot()));
        }
        return out.toString();
    }

    private static String encodeAgentYaws(Minecraft mc) {
        StringBuilder out = new StringBuilder();
        for (RLOperator agent : collectAgents(mc)) {
            if (!out.isEmpty()) {
                out.append("|");
            }
            out.append(String.format(Locale.US, "%.6f", (double) agent.getYRot()));
        }
        return out.toString();
    }

    private static String encodeAgentPitches(Minecraft mc) {
        StringBuilder out = new StringBuilder();
        for (RLOperator agent : collectAgents(mc)) {
            if (!out.isEmpty()) {
                out.append("|");
            }
            out.append(String.format(Locale.US, "%.6f", (double) agent.getXRot()));
        }
        return out.toString();
    }

    private static List<double[]> parseRows(String raw, int width) {
        java.util.ArrayList<double[]> rows = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank() || width < 1) {
            return rows;
        }
        String[] rowStrings = raw.split("\\|");
        for (String row : rowStrings) {
            String trimmed = row.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(",");
            double[] vals = new double[width];
            for (int i = 0; i < width; i++) {
                if (i < parts.length) {
                    try {
                        vals[i] = Double.parseDouble(parts[i].trim());
                    } catch (Exception ignored) {
                        vals[i] = 0.0;
                    }
                }
            }
            rows.add(vals);
        }
        return rows;
    }

    private static List<Float> parseScalars(String raw) {
        java.util.ArrayList<Float> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String[] parts = raw.split("\\|");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(Float.parseFloat(trimmed));
            } catch (Exception ignored) {
                out.add(0.0f);
            }
        }
        return out;
    }

    private static String buildMcBootstrapSource() {
        return """
import xos

def _parse_position(raw):
    if not raw:
        return (0.0, 0.0, 0.0)
    parts = [p.strip() for p in str(raw).split(",")]
    if len(parts) < 3:
        return (0.0, 0.0, 0.0)
    try:
        return (float(parts[0]), float(parts[1]), float(parts[2]))
    except Exception:
        return (0.0, 0.0, 0.0)

def _parse_rotation(raw):
    if not raw:
        return (0.0, 0.0)
    parts = [p.strip() for p in str(raw).split(",")]
    if len(parts) < 2:
        return (0.0, 0.0)
    try:
        return (float(parts[0]), float(parts[1]))
    except Exception:
        return (0.0, 0.0)

def _tensor_flat_values(t):
    if hasattr(t, "__getitem__"):
        try:
            data_dict = t[:]
            if isinstance(data_dict, dict) and "_data" in data_dict:
                return list(data_dict["_data"])
        except Exception:
            pass
    if isinstance(t, dict) and "_data" in t:
        return list(t["_data"])
    return []

def _tensor_to_rows(t, width):
    flat = _tensor_flat_values(t)
    if width <= 0 or not flat:
        return ""
    rows = len(flat) // width
    return "|".join(
        ",".join(str(float(flat[i * width + j])) for j in range(width))
        for i in range(rows)
    )

def _tensor_to_scalars(t):
    flat = _tensor_flat_values(t)
    if not flat:
        return ""
    return "|".join(str(float(v)) for v in flat)

def _rows_to_tensor(raw, width):
    if not raw:
        return xos.zeros((0, width))
    rows = [r for r in str(raw).split("|") if r]
    flat = [
        float(v)
        for r in rows
        for v in r.split(",")[:width]
    ]
    return xos.tensor(flat, (len(rows), width))

def _scalars_to_tensor(raw):
    if not raw:
        return xos.zeros((0,))
    vals = [float(v) for v in str(raw).split("|") if v]
    return xos.tensor(vals, (len(vals),))

def _value_to_rows(value, width, count):
    if width <= 0:
        return []
    rows = []
    if hasattr(value, "__getitem__"):
        try:
            data_dict = value[:]
            if isinstance(data_dict, dict) and "_data" in data_dict:
                flat = list(data_dict["_data"])
                if len(flat) == width:
                    rows = [tuple(float(flat[j]) for j in range(width))]
                elif len(flat) % width == 0:
                    n = len(flat) // width
                    rows = [
                        tuple(float(flat[i * width + j]) for j in range(width))
                        for i in range(n)
                    ]
        except Exception:
            pass
    if not rows:
        if isinstance(value, (list, tuple)):
            if len(value) == width and all(not isinstance(v, (list, tuple)) for v in value):
                rows = [tuple(float(v) for v in value)]
            elif len(value) > 0 and all(isinstance(v, (list, tuple)) for v in value):
                rows = [
                    tuple(float(v[j]) if j < len(v) else 0.0 for j in range(width))
                    for v in value
                ]
    if not rows:
        return []
    if len(rows) == 1 and count > 1:
        rows = rows * count
    if count > 0:
        rows = rows[:count]
    return rows

def _rows_to_wire(rows, width):
    if not rows or width <= 0:
        return ""
    return "|".join(
        ",".join(str(float(row[j])) for j in range(width))
        for row in rows
    )

def _format_position(value):
    if hasattr(value, "__iter__"):
        vals = list(value)
    else:
        vals = [value]
    if len(vals) < 3:
        vals = vals + [0.0] * (3 - len(vals))
    return f"{float(vals[0])},{float(vals[1])},{float(vals[2])}"

def _format_rotation(value):
    if hasattr(value, "__iter__"):
        vals = list(value)
    else:
        vals = [value]
    if len(vals) < 2:
        vals = vals + [0.0] * (2 - len(vals))
    return f"{float(vals[0])},{float(vals[1])}"

def _parse_ids(raw):
    if not raw:
        return []
    text = str(raw).strip()
    if not text:
        return []
    return [x for x in text.split("|") if x]

class Actions:
    def __init__(self, entity_kind, entity_id=""):
        self._kind = str(entity_kind)
        self._id = str(entity_id)

    def _call(self, action_name, payload=""):
        arg = f"{self._kind};{self._id};{action_name};{payload}"
        __module__._host_call("entity_action", arg)

    def w(self):
        self._call("w", "")

    def a(self):
        self._call("a", "")

    def s(self):
        self._call("s", "")

    def d(self):
        self._call("d", "")

    def jump(self):
        self._call("jump", "")

    def look(self, pitch, yaw):
        self._call("look", f"{float(pitch)},{float(yaw)}")

class Player:
    def __init__(self):
        self._actions = Actions("player", "")

    @property
    def position(self):
        raw = __module__._host_call("player_position", "")
        return _parse_position(raw)

    @position.setter
    def position(self, value):
        __module__._host_call("player_set_position", _format_position(value))

    @property
    def rotation(self):
        raw = __module__._host_call("player_rotation", "")
        return _parse_rotation(raw)

    @rotation.setter
    def rotation(self, value):
        __module__._host_call("player_set_rotation", _format_rotation(value))

    @property
    def yaw(self):
        return self.rotation[0]

    @yaw.setter
    def yaw(self, value):
        __module__._host_call("player_set_yaw", str(float(value)))

    @property
    def pitch(self):
        return self.rotation[1]

    @pitch.setter
    def pitch(self, value):
        __module__._host_call("player_set_pitch", str(float(value)))

    @property
    def velocity(self):
        raw = __module__._host_call("player_velocity", "")
        return _parse_position(raw)

    @velocity.setter
    def velocity(self, value):
        __module__._host_call("player_set_velocity", _format_position(value))

    @property
    def looking_velocity(self):
        raw = __module__._host_call("player_looking_velocity", "")
        return _parse_position(raw)

    @looking_velocity.setter
    def looking_velocity(self, value):
        __module__._host_call("player_set_looking_velocity", _format_position(value))

    @property
    def actions(self):
        return self._actions

class Agent:
    def __init__(self, agent_id):
        self._id = str(agent_id)
        self._actions = Actions("agent", self._id)

    @property
    def position(self):
        raw = __module__._host_call("agent_position", self._id)
        return _parse_position(raw)

    @position.setter
    def position(self, value):
        __module__._host_call("agent_set_position", f"{self._id};{_format_position(value)}")

    @property
    def rotation(self):
        raw = __module__._host_call("agent_rotation", self._id)
        return _parse_rotation(raw)

    @rotation.setter
    def rotation(self, value):
        __module__._host_call("agent_set_rotation", f"{self._id};{_format_rotation(value)}")

    @property
    def yaw(self):
        return self.rotation[0]

    @yaw.setter
    def yaw(self, value):
        __module__._host_call("agent_set_yaw", f"{self._id};{float(value)}")

    @property
    def pitch(self):
        return self.rotation[1]

    @pitch.setter
    def pitch(self, value):
        __module__._host_call("agent_set_pitch", f"{self._id};{float(value)}")

    @property
    def velocity(self):
        raw = __module__._host_call("agent_velocity", self._id)
        return _parse_position(raw)

    @velocity.setter
    def velocity(self, value):
        __module__._host_call("agent_set_velocity", f"{self._id};{_format_position(value)}")

    @property
    def looking_velocity(self):
        raw = __module__._host_call("agent_looking_velocity", self._id)
        return _parse_position(raw)

    @looking_velocity.setter
    def looking_velocity(self, value):
        __module__._host_call("agent_set_looking_velocity", f"{self._id};{_format_position(value)}")

    @property
    def actions(self):
        return self._actions

class Agents:
    def _list(self):
        raw = __module__._host_call("agent_ids", "")
        ids = _parse_ids(raw)
        return [Agent(agent_id) for agent_id in ids]

    def __iter__(self):
        return iter(self._list())

    def __len__(self):
        return len(self._list())

    def __getitem__(self, index):
        return self._list()[index]

    def look(self, value):
        count = len(self)
        rows = _value_to_rows(value, 2, count)
        __module__._host_call("agents_look", _rows_to_wire(rows, 2))

    def move(self, value):
        count = len(self)
        rows = _value_to_rows(value, 4, count)
        __module__._host_call("agents_move", _rows_to_wire(rows, 4))

    @property
    def positions(self):
        raw = __module__._host_call("agents_positions", "")
        return _rows_to_tensor(raw, 3)

    @positions.setter
    def positions(self, tensor):
        __module__._host_call("agents_set_positions", _tensor_to_rows(tensor, 3))

    @property
    def rotations(self):
        raw = __module__._host_call("agents_rotations", "")
        return _rows_to_tensor(raw, 2)

    @rotations.setter
    def rotations(self, tensor):
        __module__._host_call("agents_set_rotations", _tensor_to_rows(tensor, 2))

    @property
    def yaws(self):
        raw = __module__._host_call("agents_yaws", "")
        return _scalars_to_tensor(raw)

    @yaws.setter
    def yaws(self, tensor):
        __module__._host_call("agents_set_yaws", _tensor_to_scalars(tensor))

    @property
    def pitches(self):
        raw = __module__._host_call("agents_pitches", "")
        return _scalars_to_tensor(raw)

    @pitches.setter
    def pitches(self, tensor):
        __module__._host_call("agents_set_pitches", _tensor_to_scalars(tensor))

__module__.player = Player()
__module__.agents = Agents()
""";
    }

    private static String invokeHostBinding(Minecraft mc, String moduleName, String functionName, String arg0) {
        if (!"mc".equals(moduleName)) {
            throw new IllegalArgumentException("Unknown host binding module: " + moduleName);
        }
        String arg = arg0 != null ? arg0 : "";
        try {
            return switch (functionName) {
                case "chat" -> {
                    runOnClientThreadSync(mc, () -> {
                        if (mc.player != null && !arg.isBlank()) {
                            mc.player.displayClientMessage(Component.literal(arg), false);
                        }
                    });
                    yield null;
                }
                case "execution_stopped" -> {
                    yield null;
                }
                case "player_position" -> {
                    if (mc.player == null) {
                        yield "0.0,0.0,0.0";
                    }
                    yield encodePosition(mc.player.getX(), mc.player.getY(), mc.player.getZ());
                }
                case "player_set_position" -> {
                    runOnClientThreadSync(mc, () -> {
                        if (mc.player == null) {
                            return;
                        }
                        double[] xyz =
                                parseTriple(arg, mc.player.getX(), mc.player.getY(), mc.player.getZ());
                        mc.player.setPos(xyz[0], xyz[1], xyz[2]);
                    });
                    yield null;
                }
                case "player_rotation" -> {
                    if (mc.player == null) {
                        yield "0.0,0.0";
                    }
                    yield encodeRotation(mc.player.getYRot(), mc.player.getXRot());
                }
                case "player_set_rotation" -> {
                    runOnClientThreadSync(mc, () -> {
                        if (mc.player == null) {
                            return;
                        }
                        float[] yp = parsePair(arg, mc.player.getYRot(), mc.player.getXRot());
                        mc.player.setYRot(yp[0]);
                        mc.player.setXRot(yp[1]);
                        mc.player.setYHeadRot(yp[0]);
                    });
                    yield null;
                }
                case "player_set_yaw" -> {
                    runOnClientThreadSync(mc, () -> {
                        if (mc.player == null) {
                            return;
                        }
                        float yaw = parsePair(arg + ",0", mc.player.getYRot(), mc.player.getXRot())[0];
                        mc.player.setYRot(yaw);
                        mc.player.setYHeadRot(yaw);
                    });
                    yield null;
                }
                case "player_set_pitch" -> {
                    runOnClientThreadSync(mc, () -> {
                        if (mc.player == null) {
                            return;
                        }
                        float pitch = parsePair("0," + arg, mc.player.getYRot(), mc.player.getXRot())[1];
                        mc.player.setXRot(pitch);
                    });
                    yield null;
                }
                case "player_velocity" -> {
                    if (mc.player == null) {
                        yield "0.0,0.0,0.0";
                    }
                    yield encodeVelocity(mc.player.getDeltaMovement());
                }
                case "player_set_velocity" -> {
                    runOnClientThreadSync(mc, () -> {
                        if (mc.player == null) {
                            return;
                        }
                        Vec3 cur = mc.player.getDeltaMovement();
                        double[] v = parseTriple(arg, cur.x, cur.y, cur.z);
                        setEntityVelocityWithServerMirror(mc, mc.player, new Vec3(v[0], v[1], v[2]));
                    });
                    yield null;
                }
                case "player_looking_velocity" -> {
                    if (mc.player == null) {
                        yield "0.0,0.0,0.0";
                    }
                    Vec3 local =
                            worldToLookingVelocity(
                                    mc.player.getDeltaMovement(), mc.player.getYRot(), mc.player.getXRot());
                    yield encodeVelocity(local);
                }
                case "player_set_looking_velocity" -> {
                    runOnClientThreadSync(mc, () -> {
                        if (mc.player == null) {
                            return;
                        }
                        double[] lv = parseTriple(arg, 0.0, 0.0, 0.0);
                        Vec3 world =
                                lookingToWorldVelocity(
                                        new Vec3(lv[0], lv[1], lv[2]),
                                        mc.player.getYRot(),
                                        mc.player.getXRot());
                        setEntityVelocityWithServerMirror(mc, mc.player, world);
                    });
                    yield null;
                }
                case "agent_ids" -> {
                    List<RLOperator> agents = collectAgents(mc);
                    if (agents.isEmpty()) {
                        yield "";
                    }
                    StringBuilder out = new StringBuilder();
                    for (RLOperator operator : agents) {
                        if (!out.isEmpty()) {
                            out.append("|");
                        }
                        out.append(operator.getStringUUID());
                    }
                    yield out.toString();
                }
                case "agent_position" -> {
                    if (mc.level == null || arg.isBlank()) {
                        yield "0.0,0.0,0.0";
                    }
                    RLOperator found = findAgentById(mc, arg);
                    if (found == null) {
                        yield "0.0,0.0,0.0";
                    }
                    yield encodePosition(found.getX(), found.getY(), found.getZ());
                }
                case "agent_set_position" -> {
                    runOnClientThreadSync(mc, () -> {
                        int idx = arg.indexOf(';');
                        if (idx <= 0) {
                            return;
                        }
                        String id = arg.substring(0, idx);
                        String xyzRaw = arg.substring(idx + 1);
                        RLOperator agent = findAgentById(mc, id);
                        if (agent == null) {
                            return;
                        }
                        double[] xyz = parseTriple(xyzRaw, agent.getX(), agent.getY(), agent.getZ());
                        agent.setPos(xyz[0], xyz[1], xyz[2]);
                    });
                    yield null;
                }
                case "agent_rotation" -> {
                    RLOperator agent = findAgentById(mc, arg);
                    if (agent == null) {
                        yield "0.0,0.0";
                    }
                    yield encodeRotation(agent.getYRot(), agent.getXRot());
                }
                case "agent_set_rotation" -> {
                    runOnClientThreadSync(mc, () -> {
                        int idx = arg.indexOf(';');
                        if (idx <= 0) {
                            return;
                        }
                        String id = arg.substring(0, idx);
                        String ypRaw = arg.substring(idx + 1);
                        RLOperator agent = findAgentById(mc, id);
                        if (agent == null) {
                            return;
                        }
                        float[] yp = parsePair(ypRaw, agent.getYRot(), agent.getXRot());
                        applyAgentRotation(agent, yp[0], yp[1]);
                        forcedAgentRotations.put(agent.getStringUUID(), new float[] {yp[0], yp[1]});
                    });
                    yield null;
                }
                case "agent_set_yaw" -> {
                    runOnClientThreadSync(mc, () -> {
                        int idx = arg.indexOf(';');
                        if (idx <= 0) {
                            return;
                        }
                        String id = arg.substring(0, idx);
                        String yawRaw = arg.substring(idx + 1);
                        RLOperator agent = findAgentById(mc, id);
                        if (agent == null) {
                            return;
                        }
                        float yaw = parsePair(yawRaw + ",0", agent.getYRot(), agent.getXRot())[0];
                        applyAgentRotation(agent, yaw, agent.getXRot());
                        forcedAgentRotations.put(agent.getStringUUID(), new float[] {yaw, agent.getXRot()});
                    });
                    yield null;
                }
                case "agent_set_pitch" -> {
                    runOnClientThreadSync(mc, () -> {
                        int idx = arg.indexOf(';');
                        if (idx <= 0) {
                            return;
                        }
                        String id = arg.substring(0, idx);
                        String pitchRaw = arg.substring(idx + 1);
                        RLOperator agent = findAgentById(mc, id);
                        if (agent == null) {
                            return;
                        }
                        float pitch = parsePair("0," + pitchRaw, agent.getYRot(), agent.getXRot())[1];
                        applyAgentRotation(agent, agent.getYRot(), pitch);
                        forcedAgentRotations.put(agent.getStringUUID(), new float[] {agent.getYRot(), pitch});
                    });
                    yield null;
                }
                case "agent_velocity" -> {
                    RLOperator agent = findAgentById(mc, arg);
                    if (agent == null) {
                        yield "0.0,0.0,0.0";
                    }
                    yield encodeVelocity(agent.getDeltaMovement());
                }
                case "agent_set_velocity" -> {
                    runOnClientThreadSync(mc, () -> {
                        int idx = arg.indexOf(';');
                        if (idx <= 0) {
                            return;
                        }
                        String id = arg.substring(0, idx);
                        String velRaw = arg.substring(idx + 1);
                        RLOperator agent = findAgentById(mc, id);
                        if (agent == null) {
                            return;
                        }
                        Vec3 cur = agent.getDeltaMovement();
                        double[] v = parseTriple(velRaw, cur.x, cur.y, cur.z);
                        setEntityVelocityWithServerMirror(mc, agent, new Vec3(v[0], v[1], v[2]));
                    });
                    yield null;
                }
                case "agent_looking_velocity" -> {
                    RLOperator agent = findAgentById(mc, arg);
                    if (agent == null) {
                        yield "0.0,0.0,0.0";
                    }
                    Vec3 local =
                            worldToLookingVelocity(
                                    agent.getDeltaMovement(), agent.getYRot(), agent.getXRot());
                    yield encodeVelocity(local);
                }
                case "agent_set_looking_velocity" -> {
                    runOnClientThreadSync(mc, () -> {
                        int idx = arg.indexOf(';');
                        if (idx <= 0) {
                            return;
                        }
                        String id = arg.substring(0, idx);
                        String velRaw = arg.substring(idx + 1);
                        RLOperator agent = findAgentById(mc, id);
                        if (agent == null) {
                            return;
                        }
                        double[] lv = parseTriple(velRaw, 0.0, 0.0, 0.0);
                        Vec3 world =
                                lookingToWorldVelocity(
                                        new Vec3(lv[0], lv[1], lv[2]),
                                        agent.getYRot(),
                                        agent.getXRot());
                        setEntityVelocityWithServerMirror(mc, agent, world);
                    });
                    yield null;
                }
                case "entity_action" -> {
                    runOnClientThreadSync(mc, () -> {
                        String[] parts = arg.split(";", 4);
                        if (parts.length < 3) {
                            return;
                        }
                        String kind = parts[0].trim();
                        String id = parts[1].trim();
                        String action = parts[2].trim();
                        String payload = parts.length >= 4 ? parts[3] : "";
                        Entity entity = switch (kind) {
                            case "player" -> mc.player;
                            case "agent" -> findAgentById(mc, id);
                            default -> null;
                        };
                        if (entity == null || action.isEmpty()) {
                            return;
                        }
                        applyEntityActionWithServerMirror(mc, entity, action, payload);
                    });
                    yield null;
                }
                case "agents_look" -> {
                    runOnClientThreadSync(mc, () -> {
                        List<RLOperator> agents = collectAgents(mc);
                        List<double[]> rows = parseRows(arg, 2);
                        int n = Math.min(agents.size(), rows.size());
                        for (int i = 0; i < n; i++) {
                            RLOperator agent = agents.get(i);
                            double[] row = rows.get(i);
                            // API order is (pitch_delta, yaw_delta)
                            applyEntityActionWithServerMirror(
                                    mc, agent, "look", row[0] + "," + row[1]);
                        }
                    });
                    yield null;
                }
                case "agents_move" -> {
                    runOnClientThreadSync(mc, () -> {
                        List<RLOperator> agents = collectAgents(mc);
                        List<double[]> rows = parseRows(arg, 4);
                        int n = Math.min(agents.size(), rows.size());
                        for (int i = 0; i < n; i++) {
                            RLOperator agent = agents.get(i);
                            double[] row = rows.get(i);
                            if (row[0] > 0.5) {
                                applyEntityActionWithServerMirror(mc, agent, "w", "");
                            }
                            if (row[1] > 0.5) {
                                applyEntityActionWithServerMirror(mc, agent, "a", "");
                            }
                            if (row[2] > 0.5) {
                                applyEntityActionWithServerMirror(mc, agent, "s", "");
                            }
                            if (row[3] > 0.5) {
                                applyEntityActionWithServerMirror(mc, agent, "d", "");
                            }
                        }
                    });
                    yield null;
                }
                case "agents_positions" -> encodeAgentPositions(mc);
                case "agents_rotations" -> encodeAgentRotations(mc);
                case "agents_yaws" -> encodeAgentYaws(mc);
                case "agents_pitches" -> encodeAgentPitches(mc);
                case "agents_set_positions" -> {
                    runOnClientThreadSync(mc, () -> {
                        List<RLOperator> agents = collectAgents(mc);
                        List<double[]> rows = parseRows(arg, 3);
                        int n = Math.min(agents.size(), rows.size());
                        for (int i = 0; i < n; i++) {
                            RLOperator agent = agents.get(i);
                            double[] xyz = rows.get(i);
                            agent.setPos(xyz[0], xyz[1], xyz[2]);
                        }
                    });
                    yield null;
                }
                case "agents_set_rotations" -> {
                    runOnClientThreadSync(mc, () -> {
                        List<RLOperator> agents = collectAgents(mc);
                        List<double[]> rows = parseRows(arg, 2);
                        int n = Math.min(agents.size(), rows.size());
                        for (int i = 0; i < n; i++) {
                            RLOperator agent = agents.get(i);
                            float yaw = (float) rows.get(i)[0];
                            float pitch = (float) rows.get(i)[1];
                            applyAgentRotation(agent, yaw, pitch);
                            forcedAgentRotations.put(agent.getStringUUID(), new float[] {yaw, pitch});
                        }
                    });
                    yield null;
                }
                case "agents_set_yaws" -> {
                    runOnClientThreadSync(mc, () -> {
                        List<RLOperator> agents = collectAgents(mc);
                        List<Float> vals = parseScalars(arg);
                        int n = Math.min(agents.size(), vals.size());
                        for (int i = 0; i < n; i++) {
                            RLOperator agent = agents.get(i);
                            float yaw = vals.get(i);
                            applyAgentRotation(agent, yaw, agent.getXRot());
                            forcedAgentRotations.put(agent.getStringUUID(), new float[] {yaw, agent.getXRot()});
                        }
                    });
                    yield null;
                }
                case "agents_set_pitches" -> {
                    runOnClientThreadSync(mc, () -> {
                        List<RLOperator> agents = collectAgents(mc);
                        List<Float> vals = parseScalars(arg);
                        int n = Math.min(agents.size(), vals.size());
                        for (int i = 0; i < n; i++) {
                            RLOperator agent = agents.get(i);
                            float pitch = vals.get(i);
                            applyAgentRotation(agent, agent.getYRot(), pitch);
                            forcedAgentRotations.put(agent.getStringUUID(), new float[] {agent.getYRot(), pitch});
                        }
                    });
                    yield null;
                }
                case "__bootstrap__" -> buildMcBootstrapSource();
                default -> throw new IllegalArgumentException("Unknown host binding: " + moduleName + "." + functionName);
            };
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("host binding call failed: " + e.getMessage(), e);
        }
    }

    private static void configureHostBindings(Minecraft mc) {
        if (hostBindingsRegistered) {
            return;
        }
        XosNative.setHostBindingCallback(
                (moduleName, functionName, arg0) ->
                        invokeHostBinding(mc, moduleName, functionName, arg0));
        XosNative.clearHostPythonModules();
        XosNative.registerHostPythonModule(
                "mc",
                new String[] {
                        "chat",
                        "player_position",
                        "player_set_position",
                        "player_rotation",
                        "player_set_rotation",
                        "player_set_yaw",
                        "player_set_pitch",
                        "player_velocity",
                        "player_set_velocity",
                        "player_looking_velocity",
                        "player_set_looking_velocity",
                        "agent_ids",
                        "agent_position",
                        "agent_set_position",
                        "agent_rotation",
                        "agent_set_rotation",
                        "agent_set_yaw",
                        "agent_set_pitch",
                        "agent_velocity",
                        "agent_set_velocity",
                        "agent_looking_velocity",
                        "agent_set_looking_velocity",
                        "entity_action",
                        "agents_look",
                        "agents_move",
                        "agents_positions",
                        "agents_rotations",
                        "agents_yaws",
                        "agents_pitches",
                        "agents_set_positions",
                        "agents_set_rotations",
                        "agents_set_yaws",
                        "agents_set_pitches",
                        "__bootstrap__"
                });
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
