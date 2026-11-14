package ark.service;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.entity.ScriptClip;
import ark.entity.ScriptProject;
import ark.exception.GlobalExceptionHandler;
import ark.integration.byteplus.Json;
import ark.repository.ScriptClipRepository;
import ark.repository.ScriptProjectRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Clip bị kiểm duyệt chặn → nhờ model ngôn ngữ (Dola-Seed) chỉ ra bằng tiếng Việt: vi phạm loại gì, đoạn nào trong
 * prompt / lời thoại, và gợi ý prompt đã sửa. BytePlus chỉ trả về "may contain sensitive information", không nói đoạn nào.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptBlockAnalyzer {
    private static final Pattern CODE = Pattern.compile("\\b((?:Input|Output)?(?:Text|Image|Video|Audio)?SensitiveContentDetected(?:\\.\\w+)?)");

    private final Config config;
    private final ScriptClipRepository clips;
    private final ScriptProjectRepository projects;
    private final ScriptPromptWriter writer;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public boolean isAnalyzing(Long clipId) {
        return running.contains(clipId);
    }

    /** Lỗi do nội dung prompt / lời thoại (không tính ảnh có mặt người thật – lỗi đó do ảnh, không do chữ). */
    public static boolean isContentBlock(String code) {
        if (code == null) return false;
        String c = code.toLowerCase(Locale.ROOT);
        return c.contains("sensitivecontentdetected") && !c.contains("privacyinformation") && !c.startsWith("inputimage")
                && !c.startsWith("inputvideo");
    }

    /** Mã lỗi kiểm duyệt nằm trong thông báo lỗi đã lưu của clip (null nếu không có). */
    public static String codeIn(String error) {
        if (error == null) return null;
        Matcher m = CODE.matcher(error);
        return m.find() ? m.group(1) : null;
    }

    /** Chạy nền sau khi clip bị chặn hẳn; lỗi (chưa bật model, mạng…) chỉ ghi log – clip vẫn giữ thông báo lỗi gốc. */
    public void analyzeLater(Long clipId, String code, String message) {
        if (config.apiKey().isEmpty() || !isContentBlock(code) || !running.add(clipId)) return;
        pool.submit(() -> {
            try {
                analyze(clipId, code, message);
            } catch (Exception e) {
                log.warn("Không phân tích được clip {} bị chặn: {}", clipId, GlobalExceptionHandler.describe(e));
            } finally {
                running.remove(clipId);
            }
        });
    }

    /** Phân tích ngay (nút trên giao diện). @return JSON phân tích đã lưu */
    public String analyzeNow(Long clipId) throws Exception {
        if (config.apiKey().isEmpty()) throw new IllegalStateException("Chưa có API key – nhập trong trang Cài đặt trước");
        if (!running.add(clipId)) throw new IllegalStateException("Đang phân tích clip này – đợi vài giây");
        try {
            ScriptClip c = clips.findById(clipId).orElseThrow();
            return analyze(clipId, codeIn(c.getError()), c.getError());
        } finally {
            running.remove(clipId);
        }
    }

    private String analyze(Long clipId, String code, String message) throws Exception {
        ScriptClip c = clips.findById(clipId).orElseThrow();
        ScriptProject p = projects.findById(c.getProjectId()).orElseThrow();
        String json = writer.explainBlock(p, c.getPrompt(), c.getDialogue(), code, message);
        clips.setBlockAnalysis(clipId, json);
        log.info("Clip {} bị chặn – phân tích: {}", c.getCode(), Json.str(Json.parse(json), "category"));
        return json;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
