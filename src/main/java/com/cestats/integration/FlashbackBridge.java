package com.cestats.integration;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Optional runtime bridge to Flashback.
 *
 * <p>Flashback is deliberately not a compile-time dependency: CE Stats must still load and
 * operate when the replay mod is absent. The two methods and the recorder field used here are
 * public in the Flashback releases currently targeted by this mod (1.21.4 and 1.21.8); resolving
 * them at runtime also keeps the three CE Stats Minecraft targets on one source set.</p>
 */
public final class FlashbackBridge implements RecordingGateway {

    private static final Logger LOG = LoggerFactory.getLogger("cestats/flashback");
    private static final String MOD_ID = "flashback";
    private static final String API_CLASS = "com.moulberry.flashback.Flashback";

    private static volatile boolean resolved;
    private static volatile Api api;

    @Override
    public boolean isAvailable() {
        return resolve() != null;
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
        if (resolvedApi == null || isRecording()) {
            return false;
        }
        try {
            resolvedApi.start.invoke(null);
            return resolvedApi.recorder.get(null) != null;
        } catch (InvocationTargetException e) {
            logInvocationFailure("开始录制", causeOf(e));
            return false;
        } catch (ReflectiveOperationException | LinkageError e) {
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
            } catch (ReflectiveOperationException | LinkageError e) {
                LOG.warn("[cestats] 检测到 Flashback，但找不到兼容的录制接口；自动录制已停用", e);
            }
            return api;
        }
    }

    private static Throwable causeOf(InvocationTargetException exception) {
        return exception.getCause() == null ? exception : exception.getCause();
    }

    private static void logInvocationFailure(String action, Throwable failure) {
        LOG.warn("[cestats] Flashback {}失败；本场自动录制可能未保存", action, failure);
    }

    private record Api(Method start, Method finish, Field recorder) {
    }
}
