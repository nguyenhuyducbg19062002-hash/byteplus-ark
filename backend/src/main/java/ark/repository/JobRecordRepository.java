package ark.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import ark.entity.JobRecord;

@Repository
public interface JobRecordRepository extends JpaRepository<JobRecord, String> {

    /** Used at startup: jobs still RUNNING after a restart can never finish. */
    @Modifying
    @Transactional
    @Query("update JobRecord j set j.status = :to, j.error = :error where j.status = :from")
    int updateStatus(@Param("from") JobRecord.Status from, @Param("to") JobRecord.Status to, @Param("error") String error);
}
