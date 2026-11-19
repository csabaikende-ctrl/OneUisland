package dev.flexy.dynamicisland.island;

/**
 * Optional hand-off of the island's audio tap to the host.
 *
 * <p>A {@link android.media.audiofx.Visualizer} on the output mix can only be enabled by one
 * client at a time: when the host process runs a second visualizer of its own (e.g. a lockscreen
 * visualizer in SystemUI), whichever is created second silently gets no data. A host that embeds
 * the island natively can set {@link #source} to its own shared, ref-counted tap; the island then
 * subscribes to it instead of creating its own visualizer. Without a source (the LSPosed module)
 * nothing changes.
 */
public final class SharedAudioTap {
    private SharedAudioTap() {}

    /** Receives FFT frames in the {@link android.media.audiofx.Visualizer#getFft} layout. */
    public interface Sink {
        void onFft(byte[] fft, int samplingRateMilliHz);
    }

    /** A shared output-mix tap; FFT data must use {@code SCALING_MODE_AS_PLAYED}. */
    public interface Source {
        /** Starts delivering FFT frames to {@code sink}; returns a handle for {@link #close}. */
        Object open(Sink sink);

        void close(Object handle);
    }

    /** Set by the host before the island starts; null means the island owns its visualizer. */
    public static volatile Source source;
}
