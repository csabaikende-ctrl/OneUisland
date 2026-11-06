package dev.flexy.dynamicisland.island;

import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import dev.flexy.dynamicisland.xposed.XLog;

final class AudioLevels {
    static final int BANDS = 5;
    private static final float[] EDGES_HZ = {50f, 160f, 450f, 1200f, 3200f, 9000f};
    private static final long RETRY_MS = 30_000;

    private final float[] levels = new float[BANDS];
    private static final float PEAK_HALF_LIFE_S = 7f;
    private static final float MEAN_TIME_S = 0.25f;
    private static final float MIN_SPAN_DB = 12f;
    private static final float MIN_FLUX_DB = 5f;

    private final float[] floorDb = new float[BANDS];
    private final float[] meanDb = new float[BANDS];
    private final float[] peakDb = new float[BANDS];
    private final float[] peakFlux = new float[BANDS];
    private boolean primed;
    private long lastFft;
    private final Handler bg;
    private Visualizer viz;
    private volatile long lastData;
    private long failedAt;
    private boolean wanted;

    AudioLevels() {
        HandlerThread t = new HandlerThread("DynamicIslandAudio");
        t.start();
        bg = new Handler(t.getLooper());
    }

    /**
     * Attaching or detaching the visualizer re-runs the effect-chain configuration of the music
     * output (audible as a short dropout, very noticeable on Exynos devices and with effects such
     * as Dolby / ViPER4Android in the chain). Callers must therefore only toggle this with
     * playback, not with island visuals, and the release is delayed so track changes and short
     * pause/play flickers don't cause a detach + re-attach.
     */
    private static final long RELEASE_DELAY_MS = 5_000;
    private final Runnable releaseRunnable = () -> {
        if (viz != null) stop();
    };

    void setActive(boolean active) {
        if (active == wanted) return;
        wanted = active;
        bg.post(() -> {
            if (active) {
                bg.removeCallbacks(releaseRunnable);
                if (viz == null) start();
            } else if (viz != null) {
                bg.removeCallbacks(releaseRunnable);
                bg.postDelayed(releaseRunnable, RELEASE_DELAY_MS);
            }
        });
    }

    boolean isLive() {
        return wanted && SystemClock.uptimeMillis() - lastData < 500;
    }

    float level(int band) {
        return levels[band];
    }

    private void start() {
        if (failedAt != 0 && SystemClock.uptimeMillis() - failedAt < RETRY_MS) return;
        try {
            Visualizer v = new Visualizer(0);
            v.setEnabled(false);
            int[] range = Visualizer.getCaptureSizeRange();
            v.setCaptureSize(Math.max(range[0], Math.min(1024, range[1])));
            v.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED);
            int rate = Math.min(Visualizer.getMaxCaptureRate(), 30_000);
            v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    onFft(fft, samplingRate);
                }
            }, rate, false, true);
            v.setEnabled(true);
            viz = v;
            failedAt = 0;
            XLog.d("visualizer started, rate=" + rate);
        } catch (Throwable t) {
            failedAt = SystemClock.uptimeMillis();
            viz = null;
            XLog.e("visualizer unavailable", t);
        }
    }

    private void stop() {
        try {
            viz.setEnabled(false);
            viz.release();
        } catch (Throwable ignored) {
        }
        viz = null;
        for (int i = 0; i < BANDS; i++) levels[i] = 0f;
        primed = false;
        lastFft = 0;
    }

    private void onFft(byte[] fft, int samplingRateMilliHz) {
        int n = fft.length;
        if (n < 8 || samplingRateMilliHz <= 0) return;
        long nowMs = SystemClock.uptimeMillis();
        float dt = lastFft == 0 ? 0.033f : Math.min(0.2f, (nowMs - lastFft) / 1000f);
        lastFft = nowMs;
        float peakDecay = (float) Math.pow(0.5, dt / PEAK_HALF_LIFE_S);
        float meanAlpha = Math.min(1f, dt / MEAN_TIME_S);
        float binHz = (samplingRateMilliHz / 1000f) / n;
        int maxBin = n / 2 - 1;
        boolean silent = true;
        for (int b = 0; b < BANDS; b++) {
            int k0 = Math.max(1, (int) (EDGES_HZ[b] / binHz));
            int k1 = Math.min(maxBin, Math.max(k0, (int) (EDGES_HZ[b + 1] / binHz)));
            float sum = 0f;
            for (int k = k0; k <= k1; k++) {
                float re = fft[2 * k];
                float im = fft[2 * k + 1];
                sum += re * re + im * im;
            }
            float rms = (float) Math.sqrt(sum / (k1 - k0 + 1));
            float db = (float) (20.0 * Math.log10(1.0 + rms));
            if (db > 8f) silent = false;

            if (!primed) {
                floorDb[b] = db;
                meanDb[b] = db;
                peakDb[b] = db + MIN_SPAN_DB;
                peakFlux[b] = MIN_FLUX_DB;
            }
            floorDb[b] += (db - floorDb[b]) * (db < floorDb[b] ? 0.2f : 0.003f);
            peakDb[b] = Math.max(db, floorDb[b] + (peakDb[b] - floorDb[b]) * peakDecay);
            peakDb[b] = Math.max(peakDb[b], floorDb[b] + MIN_SPAN_DB);
            float body = clamp01((db - floorDb[b]) / (peakDb[b] - floorDb[b]));

            float flux = Math.max(0f, db - meanDb[b]);
            meanDb[b] += (db - meanDb[b]) * meanAlpha;
            peakFlux[b] = Math.max(flux, peakFlux[b] * peakDecay);
            peakFlux[b] = Math.max(peakFlux[b], MIN_FLUX_DB);
            float beat = clamp01(flux / peakFlux[b]);

            float v = clamp01(0.3f * body + 0.8f * beat * (0.4f + 0.6f * body));
            levels[b] = (float) Math.pow(v, 1.5);
        }
        primed = true;
        if (!silent) lastData = nowMs;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
