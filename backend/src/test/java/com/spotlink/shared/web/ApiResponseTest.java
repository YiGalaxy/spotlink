package com.spotlink.shared.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.identity.dto.LoginResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 校验前端所依赖的响应契约及超出 JavaScript 安全整数的 ID 往返。 */
class ApiResponseTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void successAndFailureUseTheSameEnvelope() throws Exception {
        var success = mapper.readTree(mapper.writeValueAsString(ApiResponse.success("中文")));
        assertThat(success.get("code").asInt()).isZero();
        assertThat(success.get("data").asText()).isEqualTo("中文");
        assertThat(success.get("timestamp").asLong()).isPositive();
        var failure = mapper.readTree(mapper.writeValueAsString(ApiResponse.failure(ResultCode.UNAUTHORIZED)));
        assertThat(failure.get("code").asInt()).isNotZero();
        assertThat(failure.get("message").asText()).isNotBlank();
    }

    @Test
    void largeIdentityIdsRemainExactStrings() throws Exception {
        var profile = new LoginResponse.UserProfile(2101635756223557634L, "测试用户", "测试", 2101635756223557635L,
                "测试企业", "T0001", 1, false, List.of(), List.of());
        var json = mapper.readTree(mapper.writeValueAsString(profile));
        assertThat(json.get("userId").isTextual()).isTrue();
        assertThat(json.get("userId").asText()).isEqualTo("2101635756223557634");
        assertThat(json.get("enterpriseId").asText()).isEqualTo("2101635756223557635");
        assertThat(mapper.readValue(json.toString(), LoginResponse.UserProfile.class)).isEqualTo(profile);
    }
}
