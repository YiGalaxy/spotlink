package com.spotlink.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthenticationBoundaryTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private com.fasterxml.jackson.databind.JsonNode login(String username) throws Exception {
        var response = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(json.writeValueAsString(java.util.Map.of("username", username, "password", "Admin@123"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0)).andReturn();
        return json.readTree(response.getResponse().getContentAsString()).path("data");
    }

    @Test void accessWorksButRefreshAndQueryParameterCannotAuthenticatePrivateEndpoint() throws Exception {
        var credentials = login("seller01");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + credentials.path("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("seller01"));
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + credentials.path("refreshToken").asText()))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").param("token", credentials.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test void changedEnterpriseOrTypeInvalidatesPreviouslyIssuedToken() throws Exception {
        var seller = login("seller01");
        var buyer = login("buyer01");
        jdbc.update("UPDATE t_user SET enterprise_id=? WHERE username='seller01'", buyer.path("user").path("enterpriseId").asText());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + seller.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
        var current = login("seller01");
        jdbc.update("UPDATE t_user SET user_type=user_type+1 WHERE username='seller01'");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + current.path("accessToken").asText()))
                .andExpect(status().isUnauthorized());
    }

    @Test void disabledAccountAndFrozenEnterpriseAreRejectedEvenWithWarmPermissionCache() throws Exception {
        var seller = login("seller01");
        String token = seller.path("accessToken").asText();
        jdbc.update("UPDATE t_user SET status=0 WHERE username='seller01'");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE t_user SET status=1 WHERE username='seller01'");
        jdbc.update("UPDATE t_enterprise SET status=3 WHERE id=?", seller.path("user").path("enterpriseId").asText());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void twoAccountsResolveDistinctEnterpriseProfiles() throws Exception {
        var seller = login("seller01");
        var buyer = login("buyer01");
        assertThat(seller.path("user").path("enterpriseId").asText()).isNotEqualTo(buyer.path("user").path("enterpriseId").asText());
        for (var credentials : new com.fasterxml.jackson.databind.JsonNode[]{seller, buyer}) {
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + credentials.path("accessToken").asText()))
                    .andExpect(jsonPath("$.data.enterpriseId").value(credentials.path("user").path("enterpriseId").asText()));
        }
    }

    @Test void inventoryQueriesAndDetailsCannotCrossEnterprise() throws Exception {
        var seller = login("seller01");
        var buyer = login("buyer01");
        Long category = jdbc.queryForObject("SELECT id FROM t_commodity_category WHERE deleted=0 LIMIT 1", Long.class);
        Long warehouse = jdbc.queryForObject("SELECT id FROM t_warehouse WHERE deleted=0 LIMIT 1", Long.class);
        jdbc.update("INSERT INTO t_inventory_note(id,note_no,enterprise_id,category_id,warehouse_id,commodity_name,total_quantity,available_quantity,frozen_quantity,unit,status) VALUES(?,?,?,?,?,?,1,1,0,'吨',2)",
                8700000000000000701L, "AUTH-ISOLATION-SELLER", seller.path("user").path("enterpriseId").asText(), category, warehouse, "隔离验收库存");
        String sellerToken = seller.path("accessToken").asText();
        String buyerToken = buyer.path("accessToken").asText();
        var own = mvc.perform(get("/api/inventory-notes").header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk()).andReturn();
        assertThat(own.getResponse().getContentAsString()).contains("AUTH-ISOLATION-SELLER");
        var other = mvc.perform(get("/api/inventory-notes").param("enterpriseId", seller.path("user").path("enterpriseId").asText())
                        .header("Authorization", "Bearer " + buyerToken)).andExpect(status().isOk()).andReturn();
        assertThat(other.getResponse().getContentAsString()).doesNotContain("AUTH-ISOLATION-SELLER");
        mvc.perform(get("/api/inventory-notes/8700000000000000701").header("Authorization", "Bearer " + buyerToken))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
    }
}
