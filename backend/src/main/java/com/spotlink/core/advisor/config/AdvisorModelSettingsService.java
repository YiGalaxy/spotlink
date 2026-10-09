package com.spotlink.advisor.config;

import com.spotlink.shared.audit.AuditService;
import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import com.spotlink.shared.security.SecurityUtils;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import com.spotlink.advisor.mapper.AdvisorModelSettingsMapper;
import com.spotlink.advisor.entity.AdvisorModelSettingsRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AdvisorModelSettingsService {
    private final AdvisorModelSettingsMapper settingsMapper;
    private final AdvisorProperties defaults;
    private final AdvisorSecretCipher cipher;
    private final AuditService audit;

    // 不缓存解密后的客户端或凭证；每个新请求读取配置，多个后端实例也能立即生效。
    public AdvisorModelSettings current() {
        AdvisorModelSettingsRow stored = stored();
        if (stored == null) return fromEnvironment();
        return new AdvisorModelSettings(stored.enabled(), stored.baseUrl(), stored.model(),
                cipher.decrypt(stored.encryptedKey()), stored.maxTokens(), stored.timeoutSeconds(), stored.tokenParameter());
    }

    public View view() {
        AdvisorModelSettingsRow stored = stored();
        if (stored == null) {
            AdvisorModelSettings config = fromEnvironment();
            return new View(config.enabled(), config.baseUrl(), config.model(), config.configured(),
                    config.maxTokens(), config.timeoutSeconds(), config.tokenParameter(), "environment", cipher.ready(), canEdit(), null);
        }
        return new View(stored.enabled(), stored.baseUrl(), stored.model(),
                stored.encryptedKey() != null && !stored.encryptedKey().isBlank(), stored.maxTokens(),
                stored.timeoutSeconds(), stored.tokenParameter(), "admin", cipher.ready(), canEdit(), stored.updatedAt());
    }

    @Transactional
    public View save(Update request) {
        String url = validateUrl(request.baseUrl());
        String parameter = request.tokenParameter();
        if (!"max_tokens".equals(parameter) && !"max_completion_tokens".equals(parameter)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "输出参数只能是 max_tokens 或 max_completion_tokens");
        }
        View before = view();
        AdvisorModelSettingsRow previous = stored();
        String key;
        if (request.clearApiKey()) key = "";
        else if (request.apiKey() != null && !request.apiKey().isBlank()) key = request.apiKey().trim();
        else key = previous == null ? fromEnvironment().apiKey() : cipher.decrypt(previous.encryptedKey());
        if (key == null || "not-configured".equals(key)) key = "";
        String encrypted = cipher.encrypt(key);
        settingsMapper.savePlatformSettings(new AdvisorModelSettingsRow(request.enabled(), url,
                request.model().trim(), encrypted, request.maxTokens(), request.timeoutSeconds(), parameter, null));
        View after = view();
        audit.record("advisor", "save-model", "platform-model", 1L, before, after);
        return after;
    }

    @Transactional
    public View reset() {
        View before = view();
        settingsMapper.deletePlatformSettings();
        View after = view();
        audit.record("advisor", "reset-model", "platform-model", 1L, before, after);
        return after;
    }

    static String validateUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!java.util.Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return value.trim().replaceAll("/+$", "");
        } catch (RuntimeException e) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "API 地址需为完整 HTTP/HTTPS 地址，不能包含凭证、查询参数或片段");
        }
    }

    private AdvisorModelSettings fromEnvironment() {
        return new AdvisorModelSettings(defaults.enabled(), defaults.baseUrl().replaceAll("/+$", ""),
                defaults.model(), defaults.apiKey(), defaults.maxTokens(), defaults.timeoutSeconds(), defaults.tokenParameter());
    }

    private AdvisorModelSettingsRow stored() {
        return settingsMapper.findPlatformSettings();
    }

    private boolean canEdit() {
        var user = SecurityUtils.currentUserOrNull();
        return user != null && user.hasPermission("admin:advisor:write");
    }

    public record View(boolean enabled, String baseUrl, String model, boolean hasKey, int maxTokens,
                       int timeoutSeconds, String tokenParameter, String source, boolean encryptionReady,
                       boolean canEdit, LocalDateTime updatedAt) {
        public boolean available() { return enabled && hasKey && ("environment".equals(source) || encryptionReady); }
    }

    public record Update(@NotNull Boolean enabled, @NotBlank @Size(max=512) String baseUrl,
                         @NotBlank @Size(max=128) String model, @Size(max=4096) String apiKey,
                         boolean clearApiKey, @NotNull @Min(64) @Max(32768) Integer maxTokens,
                         @NotNull @Min(5) @Max(300) Integer timeoutSeconds,
                         @NotBlank String tokenParameter) {
        @Override public String toString() { return "Update[credentials=REDACTED]"; }
    }
}
