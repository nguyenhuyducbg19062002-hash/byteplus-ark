package ark.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import ark.entity.ScriptProject;

@Repository
public interface ScriptProjectRepository extends JpaRepository<ScriptProject, Long> {
    List<ScriptProject> findAllByOrderByCreatedAtDesc();

    List<ScriptProject> findByStatus(ScriptProject.Status status);

    @Modifying
    @Transactional
    @Query("update ScriptProject p set p.status = :to, p.pausedReason = :reason where p.status = :from")
    int updateStatus(ScriptProject.Status from, ScriptProject.Status to, String reason);
}
