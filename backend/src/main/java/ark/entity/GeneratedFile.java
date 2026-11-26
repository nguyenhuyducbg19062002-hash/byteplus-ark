package ark.entity;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A file saved in the output folder: where it came from (original API URL, valid 24h),
 * and whether it has been pushed to the Virtual Portrait library.
 */
@Entity
@Table(name = "generated_file", indexes = {
        @Index(name = "ux_generated_file_path", columnList = "path", unique = true),
        @Index(name = "ix_generated_file_asset", columnList = "assetId"),
        @Index(name = "ix_generated_file_project", columnList = "projectId")
})
@Getter
@Setter
@NoArgsConstructor
public class GeneratedFile {
    /** Original output URLs live 24h; keep a safety margin. */
    public static final Duration SOURCE_URL_TTL = Duration.ofHours(23).plusMinutes(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Path relative to the output folder, forward slashes (e.g. images/IMG_20261111_1.jpeg). */
    @Column(nullable = false, length = 500)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MediaKind kind;

    @Column(length = 2000)
    private String sourceUrl;

    @Column(length = 80)
    private String assetId;

    /** Processing / Active / Failed */
    @Column(length = 20)
    private String assetStatus;

    @Column(length = 500)
    private String assetError;

    @Column(length = 80)
    private String model;

    @Column(length = 4000)
    private String prompt;

    @Column(length = 80)
    private String taskId;

    @Column(length = 20)
    private String jobId;

    /**
     * Dự án kịch bản sở hữu file (ảnh tham chiếu, video clip). null = không thuộc dự án nào (ảnh / video tạo lẻ).
     * Mỗi dự án chỉ dùng ảnh của chính nó – nhiều dự án trùng mã (CHAR_X_01) vẫn là ảnh khác nhau.
     */
    private Long projectId;


    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean isSourceUrlValid() {
        return sourceUrl != null && createdAt != null && createdAt.plus(SOURCE_URL_TTL).isAfter(Instant.now());
    }

    public String getName() {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
