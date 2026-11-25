package ark.service;

import java.io.IOException;

import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.dto.ConnectionTestDto;
import ark.dto.SettingsDto;
import ark.dto.SettingsUpdateRequest;
import ark.exception.GlobalExceptionHandler;
import ark.integration.byteplus.ArkClient;
import ark.integration.byteplus.AssetApi;
import ark.integration.byteplus.Json;
import lombok.RequiredArgsConstructor;

/** Settings live in %USERPROFILE%\.byteplus-ark\config.properties (secrets never go to the DB or the UI). */
@Service
@RequiredArgsConstructor
public class SettingsService {
    private final Config config;

    public SettingsDto get() {
        return SettingsDto.builder()
                .apiKey(mask(config.apiKey()))
                .apiKeySet(!config.apiKey().isEmpty())
                .baseUrl(config.baseUrl())
                .outputDir(config.outputDir().toAbsolutePath().toString())
                .accessKey(mask(config.accessKey()))
                .secretKey(mask(config.secretKey()))
                .akskSet(!config.accessKey().isEmpty() && !config.secretKey().isEmpty())
                .projectName(config.projectName())
                .assetGroup(config.assetGroup())
                .autoPush(config.autoPush())
                .configFile(config.file().toString())
                .botsdUsername(config.botsdUsername())
                .botsdLoggedIn(!config.botsdToken().isEmpty())
                .build();
    }

    public SettingsDto update(SettingsUpdateRequest u) throws IOException {
        if (isNew(u.getApiKey())) config.setApiKey(u.getApiKey());
        if (u.getBaseUrl() != null) config.setBaseUrl(u.getBaseUrl());
        if (u.getOutputDir() != null) config.setOutputDir(u.getOutputDir());
        if (isNew(u.getAccessKey())) config.setAccessKey(u.getAccessKey());
        if (isNew(u.getSecretKey())) config.setSecretKey(u.getSecretKey());
        if (u.getProjectName() != null) config.setProjectName(u.getProjectName());
        if (u.getAssetGroup() != null) config.setAssetGroup(u.getAssetGroup());
        if (u.getAutoPush() != null) config.setAutoPush(u.getAutoPush());
        config.save();
        return get();
    }

    public void setAssetGroup(String groupId) throws IOException {
        config.setAssetGroup(groupId);
        config.save();
    }

    /** API key → list video tasks; AK/SK → list asset groups. */
    public ConnectionTestDto testConnection() {
        boolean apiOk = false;
        String api;
        try {
            api = "OK – số task video: " + Json.str(ArkClient.from(config).listVideoTasks(1, 1, null, null), "total");
            apiOk = true;
        } catch (Exception e) {
            api = GlobalExceptionHandler.describe(e);
        }
        boolean akOk = false;
        String ak;
        if (config.accessKey().isEmpty()) {
            ak = "Chưa nhập AK/SK";
        } else {
            try {
                ak = "OK – số asset group: " + AssetApi.from(config).listGroups().size();
                akOk = true;
            } catch (Exception e) {
                ak = GlobalExceptionHandler.describe(e);
            }
        }
        return new ConnectionTestDto(apiOk, api, akOk, ak);
    }

    private static boolean isNew(String v) {
        return v != null && !v.contains("…");
    }

    static String mask(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= 8) return "…";
        return s.substring(0, 4) + "…" + s.substring(s.length() - 4);
    }
}
