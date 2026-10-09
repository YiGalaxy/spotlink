package com.spotlink.advisor.mapper;

import com.spotlink.advisor.entity.AdvisorModelSettingsRow;
import org.apache.ibatis.annotations.*;

public interface AdvisorModelSettingsMapper {
    @Select("""
            SELECT enabled, base_url AS baseUrl, model, encrypted_api_key AS encryptedKey,
                   max_tokens AS maxTokens, timeout_seconds AS timeoutSeconds,
                   token_parameter AS tokenParameter, updated_at AS updatedAt
              FROM t_advisor_model_settings WHERE id=1
            """)
    AdvisorModelSettingsRow findPlatformSettings();

    @Insert("""
            INSERT INTO t_advisor_model_settings
                (id,enabled,base_url,model,encrypted_api_key,max_tokens,timeout_seconds,token_parameter,updated_at)
            VALUES (1,#{row.enabled},#{row.baseUrl},#{row.model},#{row.encryptedKey},#{row.maxTokens},
                    #{row.timeoutSeconds},#{row.tokenParameter},CURRENT_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),base_url=VALUES(base_url),model=VALUES(model),
                encrypted_api_key=VALUES(encrypted_api_key),max_tokens=VALUES(max_tokens),
                timeout_seconds=VALUES(timeout_seconds),token_parameter=VALUES(token_parameter),updated_at=VALUES(updated_at)
            """)
    int savePlatformSettings(@Param("row") AdvisorModelSettingsRow row);

    @Delete("DELETE FROM t_advisor_model_settings WHERE id=1")
    int deletePlatformSettings();
}
