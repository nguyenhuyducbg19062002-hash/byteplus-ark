package ark.dto;

import lombok.Builder;

/** Secrets are only returned masked (e.g. "abcd…wxyz"). */
@Builder
public record SettingsDto(String apiKey, boolean apiKeySet, String baseUrl, String outputDir, String accessKey,
                          String secretKey, boolean akskSet, String projectName, String assetGroup, boolean autoPush,
                          String configFile) {}
