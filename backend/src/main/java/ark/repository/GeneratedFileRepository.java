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
}
