package dev.flexy.dynamicisland.island;

import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.SystemClock;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.flexy.dynamicisland.xposed.XLog;

final class MediaSource {
    private static final long PAUSED_TIMEOUT_MS = 60_000;

    private final Context ctx;
    private final Handler main;
    private final Runnable onChange;
    private final Map<MediaSession.Token, Tracked> tracked = new HashMap<>();
    private MediaSessionManager msm;
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = this::sync;

    MediaSource(Context ctx, Handler main, Runnable onChange) {
        this.ctx = ctx;
        this.main = main;
        this.onChange = onChange;
    }

    void start() {
        try {
            msm = ctx.getSystemService(MediaSessionManager.class);
            msm.addOnActiveSessionsChangedListener(sessionsListener, null, main);
            sync(msm.getActiveSessions(null));
        } catch (Throwable t) {
            XLog.e("media sessions unavailable", t);
        }
    }

    private void sync(List<MediaController> controllers) {
        Map<MediaSession.Token, Tracked> fresh = new HashMap<>();
        if (controllers != null) {
            for (MediaController c : controllers) {
                Tracked t = tracked.remove(c.getSessionToken());
                if (t == null) t = new Tracked(c);
                fresh.put(c.getSessionToken(), t);
            }
        }
        for (Tracked gone : tracked.values()) gone.release();
        tracked.clear();
        tracked.putAll(fresh);
        onChange.run();
    }

    void collect(List<IslandItem> out, String foreground, Set<String> hiddenByForeground, Collection<String> packages) {
        Tracked best = null;
        long now = SystemClock.uptimeMillis();
        for (Tracked t : tracked.values()) {
            if (!t.hasContent() || VoiceLevels.PACKAGE.equals(t.controller.getPackageName())) continue;
            if (t.isPlaying()) {
                if (best == null || !best.isPlaying() || t.lastActive > best.lastActive) best = t;
            } else if (t.lastActive > 0 && now - t.lastActive < PAUSED_TIMEOUT_MS) {
                if (best == null || (!best.isPlaying() && t.lastActive > best.lastActive)) best = t;
            }
        }
        if (best == null) return;
        if (!best.isPlaying()) {
            long wait = PAUSED_TIMEOUT_MS - (now - best.lastActive) + 50;
            main.removeCallbacks(onChange);
            main.postDelayed(onChange, Math.max(50, wait));
        }
        String pkg = best.controller.getPackageName();
        packages.add(pkg);
        IslandItem item = best.item();
        if (pkg.equals(foreground)) {
            hiddenByForeground.add(item.key);
            return;
        }
        out.add(item);
    }

    /**
     * True while any tracked media session (other than Voice Recorder) is playing, whether or not
     * its item is currently shown. The audio visualizer is kept attached for as long as this is
     * true: attaching or detaching it reconfigures the output's effect chain, which makes the music
     * glitch, so that must only happen when playback starts or stops, never on an island
     * transition (opening the card, screen off, alerts, the player app coming to the foreground).
     */
    boolean anyPlaying() {
        for (Tracked t : tracked.values()) {
            if (t.isPlaying() && t.hasContent() && !VoiceLevels.PACKAGE.equals(t.controller.getPackageName())) {
                return true;
            }
        }
        return false;
    }

    IslandItem itemForSession(MediaSession.Token token) {
        Tracked t = tracked.get(token);
        if (t != null && VoiceLevels.PACKAGE.equals(t.controller.getPackageName())) return null;
        return t != null && t.hasContent() ? t.item() : null;
    }

    private final class Tracked extends MediaController.Callback {
        final MediaController controller;
        PlaybackState state;
        MediaMetadata meta;
        long lastActive;
        IslandItem cached;
        boolean cachedRecents;
        Bitmap cachedArt;
        int cachedAccent = 0xFFFFFFFF;

        Tracked(MediaController c) {
            controller = c;
            state = c.getPlaybackState();
            meta = c.getMetadata();
            if (isPlaying()) lastActive = SystemClock.uptimeMillis();
            c.registerCallback(this, main);
            if (SpotifyRecents.PACKAGE.equals(c.getPackageName()) && IslandSettings.spotifyRecents()) {
                SpotifyRecents.get().request(ctx, this::recentsChanged);
            }
        }

        void release() {
            try {
                controller.unregisterCallback(this);
            } catch (Throwable ignored) {
            }
        }

        boolean isPlaying() {
            if (state == null) return false;
            int s = state.getState();
            return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
                    || s == PlaybackState.STATE_FAST_FORWARDING || s == PlaybackState.STATE_REWINDING;
        }

        boolean hasContent() {
            return meta != null && (meta.getString(MediaMetadata.METADATA_KEY_TITLE) != null
                    || meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) != null);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            boolean wasPlaying = isPlaying();
            state = s;
            if (isPlaying() || wasPlaying) lastActive = SystemClock.uptimeMillis();
            cached = null;
            onChange.run();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            meta = m;
            cached = null;
            onChange.run();
        }

        @Override
        public void onSessionDestroyed() {
            release();
            tracked.remove(controller.getSessionToken());
            onChange.run();
        }

        void recentsChanged() {
            cached = null;
            onChange.run();
        }

        IslandItem item() {
            String pkg = controller.getPackageName();
            boolean recents = SpotifyRecents.PACKAGE.equals(pkg) && IslandSettings.spotifyRecents();
            if (cached != null && cachedRecents == recents) return cached;
            cachedRecents = recents;
            IslandItem it = new IslandItem("media:" + pkg);
            it.kind = IslandItem.Kind.MEDIA;
            it.isMedia = true;
            it.packageName = pkg;
            it.playing = isPlaying();
            CharSequence title = meta.getText(MediaMetadata.METADATA_KEY_TITLE);
            if (title == null) title = meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            CharSequence artist = meta.getText(MediaMetadata.METADATA_KEY_ARTIST);
            if (artist == null) artist = meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            if (artist == null) artist = meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            it.title = title;
            it.subtitle = artist;
            it.appName = appLabel(pkg);
            Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            art = resolveArt(pkg, art, String.valueOf(title) + '|' + artist);
            if (art != cachedArt) {
                cachedArt = art;
                ArtMemory mem = artMemory.get(pkg);
                cachedAccent = art == null ? 0xFFFFFFFF : (mem != null && mem.art == art ? mem.accent : vibrant(art));
            }
            Drawable appIcon = appIcon(pkg);
            it.art = art;
            if (art != null) {
                it.icon = new BitmapDrawable(ctx.getResources(), art);
                it.cardImage = new BitmapDrawable(ctx.getResources(), art);
            } else {
                it.icon = appIcon;
                it.cardImage = appIcon;
            }
            it.iconIsImage = true;
            it.cardImageIsImage = true;
            it.accent = cachedAccent;
            it.trailing = IslandItem.Trailing.WAVEFORM;
            long duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
            it.mediaDuration = duration > 0 ? duration : -1;
            if (state != null) {
                it.mediaPosition = state.getPosition();
                it.mediaPositionUpdateTime = state.getLastPositionUpdateTime();
                it.mediaSpeed = state.getPlaybackSpeed() == 0f ? 1f : state.getPlaybackSpeed();
            }
            PendingIntent session = controller.getSessionActivity();
            it.contentIntent = session;
            MediaController.TransportControls tc = controller.getTransportControls();
            it.mediaPrev = tc::skipToPrevious;
            it.mediaNext = tc::skipToNext;
            it.mediaPlayPause = () -> {
                if (isPlaying()) tc.pause();
                else tc.play();
            };
            it.appIcon = appIcon;
            if (appIcon instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                Drawable mono = ((android.graphics.drawable.AdaptiveIconDrawable) appIcon).getMonochrome();
                if (mono != null) it.appGlyph = mono.mutate();
            }
            if (state != null && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0) it.mediaSeek = tc::seekTo;
            if (state != null && state.getCustomActions() != null) {
                for (PlaybackState.CustomAction ca : state.getCustomActions()) {
                    Drawable icon = null;
                    try {
                        icon = android.graphics.drawable.Icon.createWithResource(pkg, ca.getIcon()).loadDrawable(ctx);
                    } catch (Throwable ignored) {
                    }
                    final PlaybackState.CustomAction action = ca;
                    it.mediaCustom.add(new IslandItem.Action(ca.getName(), icon, null,
                            () -> tc.sendCustomAction(action, action.getExtras()), false));
                    if (it.mediaCustom.size() == 2) break;
                }
            }
            if (recents) {
                SpotifyRecents.get().request(ctx, this::recentsChanged);
                for (SpotifyRecents.Entry e : SpotifyRecents.get().entries()) {
                    it.mediaShortcuts.add(new IslandItem.Shortcut(e.title, e.art, () -> SpotifyRecents.get().play(ctx, e.id)));
                }
            }
            it.postTime = lastActive;
            cached = it;
            return it;
        }
    }

    private static final class ArtMemory {
        Bitmap art;
        int accent = 0xFFFFFFFF;
        String track;
        String waitingTrack;
        long waitingSince;
    }

    private static final long ART_GRACE_MS = 1500;
    private final Map<String, ArtMemory> artMemory = new HashMap<>();

    private Bitmap resolveArt(String pkg, Bitmap art, String track) {
        ArtMemory mem = artMemory.get(pkg);
        if (mem == null) {
            mem = new ArtMemory();
            artMemory.put(pkg, mem);
        }
        if (art != null) {
            if (mem.art != null && mem.art != art && sameArt(mem.art, art)) art = mem.art;
            if (art != mem.art) {
                mem.art = art;
                mem.accent = vibrant(art);
            }
            mem.track = track;
            mem.waitingTrack = null;
            return art;
        }
        if (mem.art == null) return null;
        if (track.equals(mem.track)) return mem.art;
        long now = SystemClock.uptimeMillis();
        if (!track.equals(mem.waitingTrack)) {
            mem.waitingTrack = track;
            mem.waitingSince = now;
        }
        long left = ART_GRACE_MS - (now - mem.waitingSince);
        if (left > 0) {
            main.postDelayed(() -> {
                for (Tracked t : tracked.values()) if (t.controller.getPackageName().equals(pkg)) t.cached = null;
                onChange.run();
            }, left + 20);
            return mem.art;
        }
        return null;
    }

    private static boolean sameArt(Bitmap a, Bitmap b) {
        try {
            return a.getWidth() == b.getWidth() && a.getHeight() == b.getHeight() && a.sameAs(b);
        } catch (Throwable t) {
            return false;
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private Drawable appIcon(String pkg) {
        try {
            return ctx.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    static int vibrant(Bitmap art) {
        try {
            Bitmap small = Bitmap.createScaledBitmap(art, 24, 24, true);
            float[] hsv = new float[3];
            float[] bucketScore = new float[12];
            float[][] bucketRgb = new float[12][3];
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    int c = small.getPixel(x, y);
                    Color.colorToHSV(c, hsv);
                    if (hsv[1] < 0.22f || hsv[2] < 0.3f) continue;
                    float score = hsv[1] * hsv[2];
                    int b = Math.min(11, (int) (hsv[0] / 30f));
                    bucketScore[b] += score;
                    bucketRgb[b][0] += Color.red(c) * score;
                    bucketRgb[b][1] += Color.green(c) * score;
                    bucketRgb[b][2] += Color.blue(c) * score;
                }
            }
            if (small != art) small.recycle();
            int best = -1;
            for (int i = 0; i < 12; i++) if (best < 0 || bucketScore[i] > bucketScore[best]) best = i;
            if (best < 0 || bucketScore[best] <= 0.5f) return 0xFFFFFFFF;
            float s = bucketScore[best];
            int color = Color.rgb(Math.round(bucketRgb[best][0] / s), Math.round(bucketRgb[best][1] / s),
                    Math.round(bucketRgb[best][2] / s));
            Color.colorToHSV(color, hsv);
            hsv[1] = Math.min(1f, hsv[1] * 1.15f);
            hsv[2] = Math.max(0.85f, hsv[2]);
            return Colors.readableOnBlack(Color.HSVToColor(hsv));
        } catch (Throwable t) {
            return 0xFFFFFFFF;
        }
    }
}
