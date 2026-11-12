package ark.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** PUT /api/v1/settings – null = unchanged; a masked value (contains "…") leaves the secret unchanged. */
@Data
public class SettingsUpdateRequest {
    @Size(max = 200)
    private String apiKey;

    @Pattern(regexp = "^$|^https?://.+", message = "Base URL phải bắt đầu bằng http(s)://")
    private String baseUrl;

    @Size(max = 500)
    private String outputDir;

    @Size(max = 200)
    private String accessKey;

    @Size(max = 200)
    private String secretKey;

    @Size(max = 64)
    private String projectName;

    @Size(max = 80)
    private String assetGroup;

    private Boolean autoPush;
}
