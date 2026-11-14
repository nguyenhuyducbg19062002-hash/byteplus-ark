package ark.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import ark.entity.ScriptReference;

@Repository
public interface ScriptReferenceRepository extends JpaRepository<ScriptReference, Long> {
    List<ScriptReference> findByProjectIdOrderByKindAscCodeAsc(Long projectId);

    Optional<ScriptReference> findByProjectIdAndCode(Long projectId, String code);

    List<ScriptReference> findByImageStatus(ScriptReference.ImageStatus status);

    long countByProjectIdAndImageStatusIn(Long projectId, java.util.Collection<ScriptReference.ImageStatus> statuses);

    @Modifying(clearAutomatically = true)
    @Transactional
    @org.springframework.data.jpa.repository.Query("update ScriptReference r set r.imageStatus = :to where r.imageStatus = :from")
    int updateImageStatus(ScriptReference.ImageStatus from, ScriptReference.ImageStatus to);

    /** Nhận mã để tạo ảnh – chỉ thành công nếu vẫn đang PENDING. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @org.springframework.data.jpa.repository.Query("update ScriptReference r set r.imageStatus = :to, r.imageError = null "
            + "where r.id = :id and r.imageStatus = :from")
    int moveImageIf(Long id, ScriptReference.ImageStatus from, ScriptReference.ImageStatus to);

    /** Chỉ ghi lý do chặn (không lưu cả entity → không ghi đè ảnh / trạng thái người dùng vừa đổi). */
    @Modifying(clearAutomatically = true)
    @Transactional
    @org.springframework.data.jpa.repository.Query("update ScriptReference r set r.blockedReason = :reason where r.id = :id")
    int setBlockedReason(Long id, String reason);

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
