package ark.entity;

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
 * Mã ảnh tham chiếu trong kịch bản (CHAR_YVO_NAVYTEE_01, LOC_01_UNIGATE, PROP_01_BACKPACK…)
 * và file ảnh trong thư viện được gán cho mã đó.
 */
@Entity
@Table(name = "script_reference", indexes = @Index(name = "ux_script_ref_code", columnList = "projectId, code", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class ScriptReference {
    public enum Kind { CHARACTER, LOCATION, PROP, OTHER }

    /** Trạng thái tạo ảnh tự động bằng Seedream. */
    public enum ImageStatus { NONE, PENDING, GENERATING, SUCCEEDED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false, length = 100)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind = Kind.OTHER;

    @Column(length = 300)
    private String name;

    @Column(length = 2000)
    private String description;

    /** GeneratedFile.id của ảnh đã gán (null = còn thiếu). */
    private Long fileId;

    /** Prompt tạo ảnh (tự sinh từ mô tả trong Excel, người dùng sửa được). */
    @Lob
    @Column(columnDefinition = "CLOB")
    private String imagePrompt;

    /** true = người dùng đã sửa prompt → nhập lại Excel không ghi đè. */
    private Boolean promptEdited;

    /** Nhân vật đổi trang phục: mã ảnh bộ đầu tiên, dùng làm tham chiếu khuôn mặt khi tạo ảnh. */
    @Column(length = 100)
    private String identityCode;

    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private ImageStatus imageStatus = ImageStatus.NONE;

    @Column(length = 2000)
    private String imageError;

    private Integer imageAttempt;

    /** BytePlus từ chối ảnh này (vd mặt người thật) → clip dùng mã này đứng chờ tới khi đổi ảnh / đẩy Virtual Portrait. */
    @Column(length = 1000)
    private String blockedReason;

    public boolean isPromptEdited() {
        return Boolean.TRUE.equals(promptEdited);
    }

    public int getImageAttempt() {
        return imageAttempt == null ? 0 : imageAttempt;
    }

    public ImageStatus getImageStatus() {
        return imageStatus == null ? ImageStatus.NONE : imageStatus;
    }

    public static Kind kindOf(String code) {
        if (code.startsWith("CHAR_")) return Kind.CHARACTER;
        if (code.startsWith("LOC_")) return Kind.LOCATION;
        if (code.startsWith("PROP_")) return Kind.PROP;
        return Kind.OTHER;
    }
}
