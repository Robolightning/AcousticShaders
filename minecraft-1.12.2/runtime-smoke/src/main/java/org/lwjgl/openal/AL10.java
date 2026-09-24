package org.lwjgl.openal;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class AL10 {
    public static final int AL_VELOCITY = 0x1006;
    public static final int AL_SOURCE_STATE = 0x1010;
    public static final int AL_INITIAL = 0x1011;
    public static final int AL_PLAYING = 0x1012;
    public static final int AL_PAUSED = 0x1013;
    public static final int AL_STOPPED = 0x1014;
    public static final int AL_BUFFER = 0x1009;
    public static final int AL_GAIN = 0x100A;
    public static final int AL_FORMAT_STEREO16 = 0x1103;

    public static int sourceiCalls, source3fCalls, lastSource, lastParam, lastValue,
        sourcePlayCalls, sourceStopCalls, deleteSourceCalls, deleteBufferCalls, bufferDataCalls;
    public static float lastX, lastY, lastZ;
    private static int nextBuffer = 500, nextSource = 700;

    public static final Map<Integer, Integer> STATES = new HashMap<Integer, Integer>();
    public static final Map<Integer, byte[]> BUFFERS = new HashMap<Integer, byte[]>();
    public static final Map<Integer, float[]> VELOCITIES = new HashMap<Integer, float[]>();
    public static final Map<Integer, Integer> DIRECT_FILTERS = new HashMap<Integer, Integer>();

    private static volatile CountDownLatch directFilterEntered;
    private static volatile CountDownLatch directFilterRelease;
    private static volatile boolean blockNextDirectFilterWrite;
    private static volatile CountDownLatch bufferWriteEntered;
    private static volatile CountDownLatch bufferWriteRelease;
    private static volatile boolean blockNextBufferWrite;

    public static void blockNextDirectFilterWrite(CountDownLatch entered, CountDownLatch release) {
        directFilterEntered = entered;
        directFilterRelease = release;
        blockNextDirectFilterWrite = true;
    }

    public static void blockNextBufferWrite(CountDownLatch entered, CountDownLatch release) {
        bufferWriteEntered = entered;
        bufferWriteRelease = release;
        blockNextBufferWrite = true;
    }

    public static void clearBlockingHooks() {
        blockNextDirectFilterWrite = false;
        directFilterEntered = null;
        directFilterRelease = null;
        blockNextBufferWrite = false;
        bufferWriteEntered = null;
        bufferWriteRelease = null;
    }

    private static void maybeBlockDirectFilterWrite(int param, int value) {
        if (param != EFX10.AL_DIRECT_FILTER || value == 0 || !blockNextDirectFilterWrite) return;
        blockNextDirectFilterWrite = false;
        awaitHook(directFilterEntered, directFilterRelease, "AL_DIRECT_FILTER");
    }

    private static void maybeBlockBufferWrite(int param, int value) {
        if (param != AL_BUFFER || value == 0 || !blockNextBufferWrite) return;
        blockNextBufferWrite = false;
        awaitHook(bufferWriteEntered, bufferWriteRelease, "AL_BUFFER");
    }

    private static void awaitHook(CountDownLatch entered, CountDownLatch release, String name) {
        if (entered != null) entered.countDown();
        if (release == null) return;
        try {
            if (!release.await(15L, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release blocked " + name + " write");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("blocked " + name + " write interrupted", e);
        }
    }

    public static void alSourcei(int source, int param, int value) {
        sourceiCalls++;
        lastSource = source;
        lastParam = param;
        lastValue = value;
        maybeBlockDirectFilterWrite(param, value);
        maybeBlockBufferWrite(param, value);
        if (param == AL_BUFFER && value != 0) STATES.put(source, AL_INITIAL);
        if (param == EFX10.AL_DIRECT_FILTER) DIRECT_FILTERS.put(source, value);
    }

    public static void alSource3f(int source, int param, float x, float y, float z) {
        source3fCalls++;
        lastSource = source;
        lastParam = param;
        lastX = x;
        lastY = y;
        lastZ = z;
        if (param == AL_VELOCITY) VELOCITIES.put(source, new float[] { x, y, z });
    }

    public static void alGetSource(int source, int param, FloatBuffer values) {
        if (param == AL_VELOCITY) {
            float[] v = VELOCITIES.get(source);
            if (v == null) v = new float[] { 0f, 0f, 0f };
            values.put(0, v[0]);
            values.put(1, v[1]);
            values.put(2, v[2]);
        }
    }

    public static void alSourcef(int source, int param, float value) {}

    public static int alGetSourcei(int source, int param) {
        if (param == AL_SOURCE_STATE) return STATES.containsKey(source) ? STATES.get(source) : AL_PLAYING;
        if (param == EFX10.AL_DIRECT_FILTER) return DIRECT_FILTERS.containsKey(source) ? DIRECT_FILTERS.get(source) : 0;
        return 0;
    }

    public static float alGetSourcef(int source, int param) {
        return param == AL11.AL_SEC_OFFSET ? 0.125f : 0f;
    }

    public static int alGenBuffers() { return nextBuffer++; }
    public static int alGenSources() { return nextSource++; }

    public static void alBufferData(int buffer, int format, ByteBuffer data, int rate) {
        ByteBuffer d = data.duplicate();
        byte[] b = new byte[d.remaining()];
        d.get(b);
        BUFFERS.put(buffer, b);
        bufferDataCalls++;
    }

    public static void alSourcePlay(int source) { STATES.put(source, AL_PLAYING); sourcePlayCalls++; }
    public static void alSourcePause(int source) { STATES.put(source, AL_PAUSED); }
    public static void alSourceStop(int source) { STATES.put(source, AL_STOPPED); sourceStopCalls++; }
    public static void alDeleteSources(int source) { STATES.remove(source); deleteSourceCalls++; }
    public static void alDeleteBuffers(int buffer) { BUFFERS.remove(buffer); deleteBufferCalls++; }
    public static int alGetError() { return 0; }
}
