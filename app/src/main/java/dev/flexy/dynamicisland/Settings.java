package dev.flexy.dynamicisland;

public final class Settings {
    public static final String FILE = "settings";
    public static final android.net.Uri URI = android.net.Uri.parse("content://dev.flexy.dynamicisland.settings");
    public static final String METHOD_GET = "get";

    public static final String MAX_CHARS = "max_chars";
    public static final int MIN_MAX_CHARS = 4;
    public static final int MAX_MAX_CHARS = 20;
    public static final int NO_MAX_CHARS = MAX_MAX_CHARS + 1;
    public static final int DEF_MAX_CHARS = NO_MAX_CHARS;

    public static final String PREVIEW_LENGTH = "preview_length";
    /** Set while the vertical position slider is dragged: the island turns white to line it up with the camera. */
    public static final String PREVIEW_ALIGN = "preview_align";

    public static final String ALERT_PODS = "alert_pods";
    public static final String PODS_EVERY_UNLOCK = "pods_every_unlock";
    public static final String ALERT_DND = "alert_dnd";
    public static final String ALERT_RINGER = "alert_ringer";

    public static final String ALERT_CHARGING = "alert_charging";
    public static final boolean DEF_ALERT_CHARGING = true;

    public static final String ALERT_BATTERY = "alert_battery";
    public static final boolean DEF_ALERT_BATTERY = true;

    public static final boolean DEF_ALERT_PODS = true;
    public static final boolean DEF_PODS_EVERY_UNLOCK = true;
    public static final boolean DEF_ALERT_DND = true;
    public static final boolean DEF_ALERT_RINGER = true;

    public static final String POSITION = "island_position";
    public static final int POS_CENTER = 0;
    public static final int POS_LEFT = 1;
    public static final int POS_CAMERA = 2;
    public static final int DEF_POSITION = POS_CENTER;

    public static final String POSITION_PHONE = "island_position_phone";
    /** Vertical nudge of the island in portrait, in dp (for ROMs whose cutout info is off). */
    public static final String OFFSET_Y = "island_offset_y";
    public static final int MIN_OFFSET_Y = -20;
    public static final int MAX_OFFSET_Y = 40;
    public static final int DEF_OFFSET_Y = 0;
    public static final String GLASS_PILL = "glass_pill";
    public static final String GLASS_PILL_PHONE = "glass_pill_phone";
    public static final boolean DEF_GLASS_PILL = false;

    /** Show incoming notifications in the island (with inline reply) instead of heads-up. */
    public static final String NOTIF_ISLAND = "notif_island";
    public static final boolean DEF_NOTIF_ISLAND = true;

    public static final String DETAILED_BUBBLE = "detailed_bubble";
    public static final boolean DEF_DETAILED_BUBBLE = false;
    public static final String FROSTED_CARDS = "frosted_cards";
    public static final boolean DEF_FROSTED_CARDS = false;
    /** Liquid glass: clear refractive glass (exclusive with {@link #FROSTED_CARDS}). */
    public static final String LIQUID_GLASS = "liquid_glass";
    public static final boolean DEF_LIQUID_GLASS = false;
    /** Backdrop blur radius used by liquid glass (SemBlurInfo radius units). */
    public static final String LIQUID_BLUR = "liquid_blur";
    public static final int MIN_LIQUID_BLUR = 4;
    public static final int MAX_LIQUID_BLUR = 60;
    public static final int DEF_LIQUID_BLUR = 16;
    /** Width of the refracting bezel at the glass edge, in dp. */
    public static final String LIQUID_EDGE = "liquid_edge";
    public static final int MIN_LIQUID_EDGE = 6;
    public static final int MAX_LIQUID_EDGE = 28;
    public static final int DEF_LIQUID_EDGE = 14;
    /** Chromatic aberration (dispersion) on the glass edge. */
    public static final String LIQUID_CHROMA = "liquid_chroma";
    public static final boolean DEF_LIQUID_CHROMA = true;

    public static final String SPOTIFY_RECENTS = "spotify_recents";
    public static final boolean DEF_SPOTIFY_RECENTS = false;

    private Settings() {
    }
}
