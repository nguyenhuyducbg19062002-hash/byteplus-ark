package ark.desktop;

import java.io.InputStream;
import java.util.Properties;

/**
 * Phiên bản + mã bản build (đọc META-INF/build-info.properties do spring-boot-maven-plugin build-info tạo).
 * Mã bản build khác nhau giữa hai lần build kể cả cùng số phiên bản → biết được Ark Studio đang chạy có phải chính bản này không.
 */
public final class AppVersion {
    private static final Properties P = new Properties();

    static {
        try (InputStream in = AppVersion.class.getResourceAsStream("/META-INF/build-info.properties")) {
            if (in != null) P.load(in);
        } catch (Exception ignored) {
            // chạy từ IDE chưa build-info → "dev"
        }
    }

    private AppVersion() {}

    /** Số phiên bản, vd 1.1.0 ("dev" khi chạy từ mã nguồn). */
    public static String version() {
        return P.getProperty("build.version", "dev");
    }

    /** Mã bản build duy nhất: phiên bản + thời điểm build. */
    public static String build() {
        return version() + "+" + P.getProperty("build.time", "dev");
    }
}
