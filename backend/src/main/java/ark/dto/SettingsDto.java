package ark.dto;

import lombok.Builder;

/** Secrets are only returned masked (e.g. "abcd…wxyz"). */
@Builder
public record SettingsDto(String apiKey, boolean apiKeySet, String baseUrl, String outputDir, String accessKey,
                          String secretKey, boolean akskSet, String projectName, String assetGroup, boolean autoPush,
                          String configFile,
                          /** BOTSD: tài khoản đã lưu và đã có phiên đăng nhập (clientToken) chưa – không trả mật khẩu / token. */
                          String botsdUsername, boolean botsdLoggedIn,
                          /** 888API: key (che bớt), đã có key chưa, nơi để link ảnh tham chiếu (TOS / LITTERBOX). */
                          String api888Key, boolean api888KeySet, String api888ImageHost) {}

