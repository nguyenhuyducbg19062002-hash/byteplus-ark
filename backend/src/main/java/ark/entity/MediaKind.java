package ark.entity;

import java.util.Locale;

public enum MediaKind {
    IMAGE, VIDEO, AUDIO;

    public static MediaKind fromFileName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4") || n.endsWith(".mov")) return VIDEO;
        if (n.endsWith(".wav") || n.endsWith(".mp3")) return AUDIO;
        return IMAGE;
    }

    /** Asset type name used by the Virtual Portrait API. */
    public String assetType() {
        return switch (this) {
            case VIDEO -> "Video";
            case AUDIO -> "Audio";
            default -> "Image";
        };
    }
}
