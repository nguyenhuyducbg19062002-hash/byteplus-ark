package ark.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một kịch bản Seedance nhập từ Excel (vd. "KICH BAN SEEDANCE S11_11.xlsx") và cấu hình chạy hàng loạt.
 * DRAFT = chưa chạy, RUNNING = đang tự gửi clip, PAUSED = dừng (có lý do), DONE = mọi clip đã xong.
 */
@Entity
@Table(name = "script_project")
@Getter
@Setter
@NoArgsConstructor
public class ScriptProject {
    public enum Status { DRAFT, RUNNING, PAUSED, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Tên file Excel đã nhập lần gần nhất. */
    @Column(length = 300)
    private String sourceFile;

    /** Thư mục con trong output/videos chứa video của dự án. */
    @Column(nullable = false, length = 120)
    private String folder;

    @Column(nullable = false, length = 80)
    private String model;

    @Column(nullable = false, length = 10)
    private String resolution;

    @Column(nullable = false, length = 10)
    private String ratio;

    private boolean generateAudio = true;

    /** Model Seedream dùng để tạo ảnh tham chiếu. */
    @Column(length = 80)
    private String imageModel;

    @Column(length = 20)
    private String imageSize;

    /** Model ngôn ngữ (Dola-Seed) viết prompt ảnh tham chiếu. null = mặc định. */
    @Column(length = 80)
    private String promptModel;

    public String getPromptModel() {
        return promptModel == null ? ark.integration.byteplus.Models.DEFAULT_TEXT_MODEL : promptModel;
    }

    /** true = ảnh AI tạo xong được duyệt luôn (không qua bước chờ duyệt). null = false. */
    private Boolean autoApproveImages;

    public boolean isAutoApproveImages() {
        return Boolean.TRUE.equals(autoApproveImages);
    }

    /** Nơi lưu ảnh tham chiếu đã duyệt: PROJECT = chỉ output/assets/&lt;dự án&gt;; FOLDER = chép thêm vào refSaveDir. */
    @Column(length = 10)
    private String refSaveMode;

    /** Thư mục riêng trên máy (FOLDER): ảnh chép vào &lt;thư mục&gt;/NHAN VAT|BOI CANH|DAO CU|KHAC/&lt;MÃ&gt;.&lt;đuôi&gt;. */
    @Column(length = 500)
    private String refSaveDir;

    public String getRefSaveMode() {
        return refSaveMode == null ? "PROJECT" : refSaveMode;
    }

    /** Số clip gửi song song tối đa. */
    private int maxParallel = 3;

    /** Số lần thử lại khi lỗi tạm thời (mạng, 5xx…). */
    private int maxAttempts = 3;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.DRAFT;

    @Column(length = 500)
    private String pausedReason;

    public void setPausedReason(String reason) {
        this.pausedReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 497) + "…";
    }

    /** Bị BytePlus giới hạn tốc độ (429) → tạm ngừng gửi clip mới đến thời điểm này. */
    private Instant cooldownUntil;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
