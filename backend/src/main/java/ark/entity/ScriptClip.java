package ark.entity;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Một clip (một dòng của sheet KỊCH BẢN) = một video Seedance.
 * PENDING → GENERATING → SUCCEEDED, hoặc FAILED / CANCELLED. Lỗi tạm thời được đưa lại PENDING cho tới maxAttempts.
 */
@Entity
@Table(name = "script_clip", indexes = {
        @Index(name = "ux_script_clip_code", columnList = "projectId, code", unique = true),
        @Index(name = "ix_script_clip_status", columnList = "projectId, status")
})
@Getter
@Setter
@NoArgsConstructor
public class ScriptClip {
    public enum Status { PENDING, GENERATING, SUCCEEDED, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    /** Mã clip trong kịch bản: 001, 002, 019A… */
    @Column(nullable = false, length = 20)
    private String code;

    /** Thứ tự trong file Excel. */
    private int seq;

    @Column(length = 300)
    private String sceneTitle;

    @Column(length = 40)
    private String timecode;

    private int duration;

    /** Mã ảnh tham chiếu theo đúng thứ tự Image 1, Image 2… (phân cách bằng dấu phẩy). */
    @Column(length = 1000)
    private String refCodes;

    @Lob
    @Column(columnDefinition = "CLOB", nullable = false)
    private String prompt;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String promptVi;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String dialogue;

    @Column(length = 2000)
    private String note;

    /** Cảnh báo khi nhập (mã thiếu, nghi lỗi thay chữ…), mỗi dòng một cảnh báo. */
    @Column(length = 4000)
    private String warnings;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status = Status.PENDING;

    private int attempt;

    /** Clip được yêu cầu làm lại có ưu tiên cao hơn. */
    private int runPriority;

    @Column(length = 80)
    private String taskId;

    @Column(length = 2000)
    private String error;

    /** Video kết quả (GeneratedFile.id). */
    private Long fileId;

    private Instant startedAt;
    private Instant finishedAt;

    public List<String> refList() {
        return refCodes == null || refCodes.isBlank() ? List.of() : Arrays.asList(refCodes.split(","));
    }

    public List<String> warningList() {
        return warnings == null || warnings.isBlank() ? List.of() : Arrays.asList(warnings.split("\n"));
    }
}
