package com.bulk.trade.advisor.tool.impl;

import com.bulk.trade.advisor.tool.AdvisorContext;
import com.bulk.trade.advisor.tool.AdvisorTool;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Reports the caller's own enterprise profile.
 *
 * <p>Note the empty schema: there is no {@code enterpriseId} argument to pass.
 * The tenant comes from {@link AdvisorContext}, so this tool physically cannot
 * be aimed at another company. Compare with the tempting alternative of
 * accepting an id and validating it afterwards — that puts the check in the
 * hands of whoever writes the next tool, and one forgotten check is a breach.
 */
@Component
@RequiredArgsConstructor
public class QueryMyEnterpriseTool implements AdvisorTool {

    private final EnterpriseMapper enterpriseMapper;

    @Override
    public String name() {
        return "query_my_enterprise";
    }

    @Override
    public String description() {
        return """
                Returns the profile of the caller's OWN enterprise: company name, trading seat
                code, review status, and contact details. Takes no arguments and always refers
                to the caller's own company. Use it when asked about the user's own account,
                company information, or registration/review status.""";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public String execute(JsonNode input, AdvisorContext context) {
        if (context.enterpriseId() == null) {
            return "该账号是平台运营账号，未绑定企业。";
        }

        Enterprise enterprise = enterpriseMapper.selectById(context.enterpriseId());
        if (enterprise == null) {
            return "未找到企业记录。";
        }

        return """
                企业名称: %s
                企业简称: %s
                企业代码: %s
                交易席位: %s
                审核状态: %s
                法定代表人: %s
                联系人: %s (%s)
                所在地: %s%s
                """.formatted(
                enterprise.getName(),
                nullSafe(enterprise.getShortName()),
                enterprise.getEnterpriseCode(),
                nullSafe(enterprise.getTraderCode(), "尚未分配"),
                statusText(enterprise.getStatus()),
                nullSafe(enterprise.getLegalPerson()),
                nullSafe(enterprise.getContactName()),
                nullSafe(enterprise.getContactPhone()),
                nullSafe(enterprise.getProvince()),
                nullSafe(enterprise.getCity()));
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Enterprise.Status.PENDING -> "待审核";
            case Enterprise.Status.APPROVED -> "已通过";
            case Enterprise.Status.REJECTED -> "已驳回";
            case Enterprise.Status.FROZEN -> "已冻结";
            case Enterprise.Status.CLOSED -> "已注销";
            default -> "未知";
        };
    }

    private String nullSafe(String value) {
        return nullSafe(value, "—");
    }

    private String nullSafe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
