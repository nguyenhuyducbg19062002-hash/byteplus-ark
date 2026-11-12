package ark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateAssetGroupRequest {
    @NotBlank(message = "Thiếu tên group")
    @Size(max = 64, message = "Tên group tối đa 64 ký tự")
    private String name;

    @Size(max = 300)
    private String description;
}
