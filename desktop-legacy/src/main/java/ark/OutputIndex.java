package ark;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Remembers, for every file saved under the output folder, the original URL returned by the API
 * (valid 24h) and the asset ID once it is pushed to the Virtual Portrait library.
 * Stored in output/index.properties. CreateAsset only accepts a public URL, so the original
 * URL is what gets "pushed back up".
 */
public final class OutputIndex {
    private static final long URL_TTL_SECONDS = 23 * 3600 + 30 * 60; // keep a safety margin under 24h

    private final Path file;

    private OutputIndex(Path outDir) {
        this.file = outDir.resolve("index.properties");
    }

    public static OutputIndex at(Path outDir) {
        return new OutputIndex(outDir);
    }

    private static String key(Path p) {
        return p.toAbsolutePath().normalize().toString().toLowerCase();
    }

    public synchronized void recordUrl(Path saved, String url) throws IOException {
        Properties p = load();
        p.setProperty(key(saved) + ".url", url);
        p.setProperty(key(saved) + ".time", String.valueOf(System.currentTimeMillis() / 1000));
        save(p);
    }

    /** Original URL if it is still valid, otherwise null. */
    public synchronized String validUrl(Path saved) throws IOException {
        Properties p = load();
        String url = p.getProperty(key(saved) + ".url");
        String t = p.getProperty(key(saved) + ".time");
        if (url == null || t == null) return null;
        long age = System.currentTimeMillis() / 1000 - Long.parseLong(t);
        return age < URL_TTL_SECONDS ? url : null;
    }

    public synchronized void recordAsset(Path saved, String assetId) throws IOException {
        Properties p = load();
        p.setProperty(key(saved) + ".asset", assetId);
        save(p);
    }

    public synchronized String asset(Path saved) throws IOException {
        return load().getProperty(key(saved) + ".asset");
    }

    private Properties load() throws IOException {
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return p;
    }

    private void save(Properties p) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            p.store(w, "BytePlus Ark Studio – original output URLs and asset IDs");
        }
    }
}
