package com.spotlink.advisor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotlink.advisor.config.AdvisorModelSettingsService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdvisorModelSettingsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AdvisorModelSettingsService settings;
    private static final String API="/api/admin/advisor-model";
    @BeforeEach void clear() { jdbc.update("DELETE FROM t_advisor_model_settings"); }
    @AfterEach void clean() { jdbc.update("DELETE FROM t_advisor_model_settings"); }

    private String token(String username) throws Exception {
        var result=mvc.perform(post("/api/auth/login").contentType("application/json")
                .content(json.writeValueAsString(Map.of("username",username,"password","Admin@123"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andReturn();
        return "Bearer "+json.readTree(result.getResponse().getContentAsString()).path("data").path("accessToken").asText();
    }
    private String update(String model, String key, boolean clear, boolean enabled) throws Exception {
        return json.writeValueAsString(Map.of("enabled",enabled,"baseUrl","http://ollama:11434/v1/",
                "model",model,"apiKey",key,"clearApiKey",clear,"maxTokens",256,"timeoutSeconds",10,"tokenParameter","max_tokens"));
    }

    @Test void membersCannotReadWriteResetOrTestAndAuditorsOnlyRead() throws Exception {
        for (String user:java.util.List.of("seller01","auditor01")) {
            String auth=token(user);
            mvc.perform(get(API).header("Authorization",auth)).andExpect(user.equals("seller01") ? status().isForbidden() : status().isOk());
            mvc.perform(put(API).header("Authorization",auth).contentType("application/json").content(update("model","offline-key",false,true))).andExpect(status().isForbidden());
            mvc.perform(delete(API).header("Authorization",auth)).andExpect(status().isForbidden());
            mvc.perform(post(API+"/test").header("Authorization",auth)).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_advisor_model_settings",Integer.class)).isZero();
    }

    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void keysAreEncryptedNotReturnedOrAuditedAndNewRequestsUseSavedModel() throws Exception {
        String admin=token("admin"), canary="offline-integration-secret";
        var response=mvc.perform(put(API).header("Authorization",admin).contentType("application/json")
                .content(update("model-one",canary,false,true))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasKey").value(true)).andExpect(jsonPath("$.data.canEdit").value(true)).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain(canary,"encrypted_api_key");
        assertThat(settings.current().apiKey()).isEqualTo(canary);
        assertThat(jdbc.queryForObject("SELECT encrypted_api_key FROM t_advisor_model_settings",String.class)).startsWith("v1:").doesNotContain(canary);
        mvc.perform(put(API).header("Authorization",admin).contentType("application/json").content(update("model-two","",false,true)))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(settings.current().apiKey()).isEqualTo(canary);
        assertThat(settings.current().model()).isEqualTo("model-two");
        var status=mvc.perform(get("/api/advisor/status").header("Authorization",token("buyer01")))
                .andExpect(jsonPath("$.data.model").value("model-two")).andExpect(jsonPath("$.data.available").value(true)).andReturn();
        assertThat(status.getResponse().getContentAsString()).doesNotContain(canary,"ollama:11434");
        var auditRows=jdbc.queryForList("SELECT before_data,after_data FROM t_audit_log WHERE module='advisor' AND action='save-model'");
        assertThat(auditRows).isNotEmpty();
        assertThat(auditRows.toString()).contains("model-one","model-two").doesNotContain(canary);
    }

    @Test void clearingAndResettingNeverBorrowTheOldKey() throws Exception {
        String admin=token("admin");
        mvc.perform(put(API).header("Authorization",admin).contentType("application/json").content(update("model","offline-key",false,true))).andExpect(jsonPath("$.code").value(0));
        mvc.perform(put(API).header("Authorization",admin).contentType("application/json").content(update("model","",true,true))).andExpect(jsonPath("$.data.hasKey").value(false));
        assertThat(settings.current().apiKey()).isEmpty();
        mvc.perform(get("/api/advisor/status").header("Authorization",admin)).andExpect(jsonPath("$.data.available").value(false));
        mvc.perform(delete(API).header("Authorization",admin)).andExpect(jsonPath("$.data.source").value("environment"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_advisor_model_settings",Integer.class)).isZero();
    }

    @Test void blankKeyKeepsPlatformStartupWorkingAndMessageDoesNotCreateOrphanRows() throws Exception {
        String buyer=token("buyer01");
        var creation=mvc.perform(post("/api/advisor/conversations").header("Authorization",buyer).contentType("application/json").content("{}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String id=json.readTree(creation.getResponse().getContentAsString()).path("data").path("id").asText();
        mvc.perform(post("/api/advisor/conversations/"+id+"/messages").header("Authorization",buyer)
                .contentType("application/json").content("{\"message\":\"你好\"}"))
                .andExpect(jsonPath("$.code").value(80000));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_ai_message WHERE conversation_id=?",Integer.class,id)).isZero();
    }

    @Test void invalidAddressAndTokenLimitsAreRejectedWithoutSaving() throws Exception {
        String admin=token("admin");
        for (String body:java.util.List.of(update("model","offline-key",false,true).replace("http://ollama:11434/v1/","file:///root"),
                update("model","offline-key",false,true).replace("256","0"))) {
            mvc.perform(put(API).header("Authorization",admin).contentType("application/json").content(body))
                    .andExpect(jsonPath("$.code").value(10000));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_advisor_model_settings",Integer.class)).isZero();
    }
}
