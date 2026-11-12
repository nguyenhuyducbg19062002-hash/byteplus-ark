package ark.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import ark.entity.ScriptClip;

@Repository
public interface ScriptClipRepository extends JpaRepository<ScriptClip, Long> {
    List<ScriptClip> findByProjectIdOrderBySeq(Long projectId);

    Page<ScriptClip> findByProjectId(Long projectId, Pageable pageable);

    Page<ScriptClip> findByProjectIdAndStatus(Long projectId, ScriptClip.Status status, Pageable pageable);

    Optional<ScriptClip> findByProjectIdAndCode(Long projectId, String code);

    long countByProjectIdAndStatus(Long projectId, ScriptClip.Status status);

    long countByStatus(ScriptClip.Status status);

    /** Hàng đợi: ưu tiên clip được làm lại, sau đó theo thứ tự kịch bản. */
    List<ScriptClip> findByProjectIdAndStatusOrderByRunPriorityDescSeqAsc(Long projectId, ScriptClip.Status status);

    @Query("select coalesce(max(c.runPriority), 0) from ScriptClip c where c.projectId = :projectId")
    int maxPriority(Long projectId);

    @Modifying
    @Transactional
    @Query("update ScriptClip c set c.status = :to where c.status = :from")
    int updateStatus(ScriptClip.Status from, ScriptClip.Status to);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update ScriptClip c set c.status = :to, c.error = :msg, c.startedAt = coalesce(:started, c.startedAt), "
            + "c.finishedAt = :finished where c.id = :id and c.status = :from")
    int moveIf(Long id, ScriptClip.Status from, ScriptClip.Status to, String msg, java.time.Instant started, java.time.Instant finished);

    /** Nhận clip để chạy – chỉ thành công nếu clip vẫn đang PENDING (tránh tranh chấp với thao tác Dừng / sửa). */
    default int claim(Long id, java.time.Instant now) {
        return moveIf(id, ScriptClip.Status.PENDING, ScriptClip.Status.GENERATING, null, now, null);
    }

    /** Huỷ clip chưa chạy – chỉ thành công nếu clip vẫn đang PENDING. */
    default int cancelPending(Long id, String msg, java.time.Instant now) {
        return moveIf(id, ScriptClip.Status.PENDING, ScriptClip.Status.CANCELLED, msg, null, now);
    }

    @Modifying
    @Transactional
    void deleteByProjectId(Long projectId);
}
