package ark.dto;

import java.util.List;

/** Kết quả nhập Excel: số clip mới / cập nhật / không đổi và các cảnh báo cần xem. */
public record ScriptImportResult(ScriptProjectDto project, int created, int updated, int unchanged,
                                 int references, int autoMatched, List<String> warnings) {}
