package ark.service;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.servlet.http.HttpServletResponse;

/**
 * Tải nhiều file về máy trong một file .zip, ghi thẳng ra response (không tạo file tạm, không giới hạn thời gian).
 * Ảnh / video vốn đã nén nên dùng mức nén thấp nhất để zip nhanh.
 */
public final class ZipExport {
    /** name = đường dẫn trong zip (vd video/005.mp4), path = file trên máy. */
    public record Item(String name, Path path) {}

    private ZipExport() {}

    public static void send(HttpServletResponse res, String zipName, List<Item> items) throws IOException {
        if (items.isEmpty()) throw new IllegalArgumentException("Không có file nào còn trên máy để tải");
        res.setContentType("application/zip");
        String enc = URLEncoder.encode(zipName, StandardCharsets.UTF_8).replace("+", "%20");
        res.setHeader("Content-Disposition", "attachment; filename=\"download.zip\"; filename*=UTF-8''" + enc);
        try (OutputStream out = res.getOutputStream(); ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.NO_COMPRESSION);
            Set<String> used = new HashSet<>();
            for (Item it : items) {
                if (!Files.isRegularFile(it.path())) continue;
                zip.putNextEntry(new ZipEntry(unique(it.name(), used)));
                Files.copy(it.path(), zip);
                zip.closeEntry();
            }
        }
    }

    /** Trùng tên trong zip → thêm (2), (3)… trước đuôi file. */
    private static String unique(String name, Set<String> used) {
        String n = name;
        int dot = name.lastIndexOf('.');
        String base = dot > name.lastIndexOf('/') ? name.substring(0, dot) : name;
        String ext = dot > name.lastIndexOf('/') ? name.substring(dot) : "";
        for (int i = 2; !used.add(n.toLowerCase()); i++) n = base + " (" + i + ")" + ext;
        return n;
    }
}
