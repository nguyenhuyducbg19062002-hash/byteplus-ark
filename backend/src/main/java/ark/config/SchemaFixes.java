package ark.config;

import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Sửa schema mà ddl-auto=update không tự sửa được.
 * <p>
 * Hibernate 6 tạo ràng buộc CHECK cho cột enum (vd {@code IMAGE_STATUS IN ('NONE', …)}) khi tạo bảng, nhưng không cập nhật
 * nó khi enum có giá trị mới → ghi giá trị mới (REVIEW) sẽ lỗi. Bỏ các CHECK cũ còn thiếu giá trị mới.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaFixes {
    private final JdbcTemplate jdbc;

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void dropStaleEnumChecks() {
        dropCheck("SCRIPT_REFERENCE", "IMAGE_STATUS", "REVIEW");
    }

    private void dropCheck(String table, String column, String requiredValue) {
        try {
            List<String> names = jdbc.queryForList(
                    "select tc.CONSTRAINT_NAME from INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                            + "join INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc on tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME "
                            + "and tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA "
                            + "where tc.TABLE_NAME = ? and cc.CHECK_CLAUSE like ? and cc.CHECK_CLAUSE not like ?",
                    String.class, table, "%" + column + "%", "%'" + requiredValue + "'%");
            for (String n : names) {
                jdbc.execute("alter table " + table + " drop constraint \"" + n.replace("\"", "") + "\"");
                log.info("Dropped stale check constraint {} on {}.{}", n, table, column);
            }
        } catch (Exception e) {
            log.warn("Cannot check constraints of {}: {}", table, e.getMessage());
        }
    }
}
