package ark.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** POST /api/v1/assets/from-url */
@Data
public class PushUrlRequest {
    @NotBlank(message = "Thiếu URL")
    @Pattern(regexp = "^https?://.+", message = "URL phải là http(s) công khai")
    @Size(max = 2000)
    private String url;

    @Pattern(regexp = "^$|Image|Video|Audio", message = "type phải là Image, Video hoặc Audio")
    private String type;

    @Size(max = 64)
    private String name;

    private String group;
}
