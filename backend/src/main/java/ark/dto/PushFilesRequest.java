package ark.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** POST /api/v1/assets – push library files (by id) into the Virtual Portrait group. */
@Data
public class PushFilesRequest {
    @NotEmpty(message = "Chưa chọn file")
    @Size(max = 100)
    private List<@NotNull Long> fileIds;

    /** Optional – defaults to the selected group in settings. */
    private String group;
}
