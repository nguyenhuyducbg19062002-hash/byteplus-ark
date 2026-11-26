package ark.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import ark.entity.GeneratedFile;
import ark.entity.MediaKind;

@Repository
public interface GeneratedFileRepository extends JpaRepository<GeneratedFile, Long> {
    Optional<GeneratedFile> findByPath(String path);

    Page<GeneratedFile> findByKind(MediaKind kind, Pageable pageable);

    List<GeneratedFile> findByAssetId(String assetId);

    List<GeneratedFile> findByIdIn(List<Long> ids);

    List<GeneratedFile> findByKindOrderByCreatedAtDesc(MediaKind kind);

    List<GeneratedFile> findByKindAndProjectIdOrderByCreatedAtDesc(MediaKind kind, Long projectId);

    List<GeneratedFile> findByProjectIdIsNull();

    List<GeneratedFile> findByProjectId(Long projectId);

    /** Lọc thư viện: all = mọi file; ngược lại pid = null → file không thuộc dự án nào, pid = id → file của dự án đó. */
    @org.springframework.data.jpa.repository.Query("select f from GeneratedFile f where (:kind is null or f.kind = :kind) and "
            + "(:all = true or (:pid is null and f.projectId is null) or f.projectId = :pid)")
    Page<GeneratedFile> search(MediaKind kind, boolean all, Long pid, Pageable pageable);

}
