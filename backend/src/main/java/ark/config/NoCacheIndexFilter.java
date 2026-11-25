package ark.config;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Cửa sổ app (Edge, hồ sơ riêng ở .byteplus-ark\webview – không bị xoá khi gỡ / cài lại) không được dùng trang cũ đã lưu:
 * <ul>
 * <li>index.html (và "/") luôn hỏi lại backend → cài bản mới là thấy giao diện mới ngay;</li>
 * <li>/assets/* có mã băm trong tên file (Vite) → lưu lâu được, bản mới có tên file mới.</li>
 * </ul>
 * Trước đây không có Cache-Control nên Edge tự đoán thời hạn và có thể hiện giao diện bản cũ hàng giờ sau khi cập nhật.
 */
@Component
public class NoCacheIndexFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path.startsWith("/assets/")) {
            res.setHeader("Cache-Control", "public, max-age=31536000, immutable");
        } else if (!path.startsWith("/api/")) {
            res.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        }
        chain.doFilter(req, res);
    }
}
