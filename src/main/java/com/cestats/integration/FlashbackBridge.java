package com.cestats.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Optional runtime bridge to Flashback.
 *
 * <p>Flashback is deliberately not a compile-time dependency: CE Stats must still load and
 * operate when the replay mod is absent. The two methods and the recorder field used here are
 * public in the Flashback releases currently targeted by this mod (1.21.4, 1.21.8 and 1.21.11); resolving
 * them at runtime also keeps the three CE Stats Minecraft targets on one source set.</p>
 *
 * <p>Timeline markers resolve separately from the start/finish pair, so a Flashback build that
 * moved {@code Recorder#addMarker} only loses marking and still records whole matches.</p>
 */
public final class FlashbackBridge implements RecordingGateway {

    private static final Logger LOG = LoggerFactory.getLogger("cestats/flashback");
    private static final String MOD_ID = "flashback";
    private static final String API_CLASS = "com.moulberry.flashback.Flashback";
    private static final String MARKER_CLASS = "com.moulberry.flashback.record.ReplayMarker";
    private static final String MARKER_POSITION_CLASS = MARKER_CLASS + "$MarkerPosition";

    private static volatile boolean resolved;
    private static volatile boolean startBroken;
    private static volatile boolean markBroken;
    private static volatile Api api;
    private static volatile Marks marks;

    @Override
    public boolean isAvailable() {
        return !startBroken && resolve() != null;
    }

    @Override
    public boolean isRecording() {
        Api resolvedApi = resolve();
        if (resolvedApi == null) {
            return false;
        }
        try {
            return resolvedApi.recorder.get(null) != null;
        } catch (IllegalAccessException | LinkageError e) {
            logInvocationFailure("读取录制状态", e);
            return false;
        }
    }

    @Override
    public boolean start() {
        Api resolvedApi = resolve();
        if (resolvedApi == null || startBroken || isRecording()) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            // The room message can arrive while the client is still joining. The controller will
            // retry from the client tick once Flashback has a registry/player to record.
            return false;
        }
        try {
            resolvedApi.start.invoke(null);
            boolean started = resolvedApi.recorder.get(null) != null;
            if (!started) {
                // The method was present but Flashback rejected this recording (for example due
                // to an incompatible installed mod). Do not hammer the entry point every tick.
                startBroken = true;
            }
            return started;
        } catch (InvocationTargetException e) {
            startBroken = true;
            logInvocationFailure("开始录制", causeOf(e));
            return false;
        } catch (ReflectiveOperationException | LinkageError e) {
            startBroken = true;
            logInvocationFailure("开始录制", e);
            return false;
        }
    }

    @Override
    public boolean finish() {
        Api resolvedApi = resolve();
        if (resolvedApi == null || !isRecording()) {
            return false;
        }
        try {
            resolvedApi.finish.invoke(null);
            return true;
        } catch (InvocationTargetException e) {
            logInvocationFailure("结束录制", causeOf(e));
            return false;
        } catch (ReflectiveOperationException | LinkageError e) {
            logInvocationFailure("结束录制", e);
            return false;
        }
    }

    @Override
    public boolean supportsMarks() {
        return !markBroken && resolve() != null && marks != null;
    }

    /**
     * Flashback keys a marker by the recorder's own written-tick counter, which only advances while
     * it is actually writing. Marking as the chat line arrives is therefore the only correct option:
     * translating an event timestamp into a tick would drift across every pause.
     *
     * <p>Must be called from the client thread — the marker map is a plain {@code TreeMap} shared
     * with the recorder's write loop.</p>
     */
    @Override
    public boolean mark(String description, int colour) {
        Api resolvedApi = resolve();
        Marks resolvedMarks = marks;
        if (resolvedApi == null || resolvedMarks == null || markBroken) {
            return false;
        }
        try {
            Object recorder = resolvedApi.recorder.get(null);
            if (recorder == null) {
                return false;
            }
            // Position stays null: the kill feed is chat text and carries no coordinates.
            Object marker = resolvedMarks.marker.newInstance(colour, null, description);
            resolvedMarks.addMarker.invoke(recorder, marker);
            return true;
        } catch (InvocationTargetException e) {
            markBroken = true;
            logMarkFailure(causeOf(e));
            return false;
        } catch (ReflectiveOperationException | LinkageError e) {
            markBroken = true;
            logMarkFailure(e);
            return false;
        }
    }

    private static Api resolve() {
        if (resolved) {
            return api;
        }
        synchronized (FlashbackBridge.class) {
            if (resolved) {
                return api;
            }
            resolved = true;

            if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
                return null;
            }

            try {
                // Do not initialize Flashback just to detect it. The first method invocation will
                // initialize it on the client thread, where its Minecraft state is available.
                Class<?> flashback = Class.forName(API_CLASS, false,
                        FlashbackBridge.class.getClassLoader());
                Method start = flashback.getMethod("startRecordingReplay");
                Method finish = flashback.getMethod("finishRecordingReplay");
                Field recorder = flashback.getField("RECORDER");
                api = new Api(start, finish, recorder);
                marks = resolveMarks(recorder.getType());
            } catch (ReflectiveOperationException | LinkageError e) {
                LOG.warn("[cestats] 检测到 Flashback，但找不到兼容的录制接口；自动录制已停用", e);
            }
            return api;
        }
    }

    /** Resolved from the declared type of {@code RECORDER}, so no second class name to keep in sync. */
    private static Marks resolveMarks(Class<?> recorderType) {
        ClassLoader loader = FlashbackBridge.class.getClassLoader();
        try {
            Class<?> markerClass = Class.forName(MARKER_CLASS, false, loader);
            Class<?> positionClass = Class.forName(MARKER_POSITION_CLASS, false, loader);
            Constructor<?> marker =
                    markerClass.getConstructor(int.class, positionClass, String.class);
            Method addMarker = recorderType.getMethod("addMarker", markerClass);
            return new Marks(marker, addMarker);
        } catch (ReflectiveOperationException | LinkageError e) {
            LOG.info("[cestats] Flashback 无兼容的时间轴标记接口；仅自动录制可用（{}）", e.toString());
            return null;
        }
    }

    private static Throwable causeOf(InvocationTargetException exception) {
        return exception.getCause() == null ? exception : exception.getCause();
    }

    private static void logInvocationFailure(String action, Throwable failure) {
        LOG.warn("[cestats] Flashback {}失败；本场自动录制可能未保存", action, failure);
    }

    /** A failed mark costs one timeline bookmark, not the recording — logged once, then given up. */
    private static void logMarkFailure(Throwable failure) {
        LOG.warn("[cestats] Flashback 写入时间轴标记失败；本次启动不再打点，录制不受影响", failure);
    }

    private record Api(Method start, Method finish, Field recorder) {
    }

    private record Marks(Constructor<?> marker, Method addMarker) {
    }
}
