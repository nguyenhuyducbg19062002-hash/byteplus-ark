package ark.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** History of background jobs (generation, asset push, download). Live logs are kept in memory while running. */
@Entity
@Table(name = "job", indexes = @Index(name = "ix_job_created", columnList = "createdAt"))
@Getter
@Setter
@NoArgsConstructor
public class JobRecord {
    public enum Status { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    @Id
    @Column(length = 20)
    private String id;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(nullable = false, length = 300)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    @Column(length = 80)
    private String taskId;

    @Column(length = 2000)
    private String error;

    /** Comma separated GeneratedFile ids produced by the job. */
    @Column(length = 1000)
    private String fileIds;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String logs;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant finishedAt;
}
