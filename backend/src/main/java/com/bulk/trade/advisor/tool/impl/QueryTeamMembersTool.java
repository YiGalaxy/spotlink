package com.bulk.trade.advisor.tool.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.advisor.tool.AdvisorContext;
import com.bulk.trade.advisor.tool.AdvisorTool;
import com.bulk.trade.identity.entity.User;
import com.bulk.trade.identity.mapper.UserMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Lists the accounts belonging to the caller's own enterprise.
 *
 * <p>The result is capped and the password hash is never selected. Tool output
 * is fed straight back into the model's context, so anything returned here is
 * effectively handed to the model — an oversized result costs money, and a
 * leaked field is a leak.
 */
@Component
@RequiredArgsConstructor
public class QueryTeamMembersTool implements AdvisorTool {

    /** Tool results are re-sent as input tokens on the next turn; keep them small. */
    private static final int MAX_ROWS = 50;

    private final UserMapper userMapper;

    @Override
    public String name() {
        return "query_team_members";
    }

    @Override
    public String description() {
        return """
                Lists the accounts under the caller's OWN enterprise: username, real name,
                account type and enabled/disabled status. Use it for questions about company
                members, sub-accounts, or who has access. It cannot return another company's
                members.""";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "include_disabled", Map.of(
                                "type", "boolean",
                                "description", "Include disabled accounts. Defaults to false.")),
                "required", List.of());
    }

    @Override
    public String execute(JsonNode input, AdvisorContext context) {
        if (context.enterpriseId() == null) {
            return "该账号是平台运营账号，未绑定企业，因此没有企业成员列表。";
        }

        boolean includeDisabled = input != null
                && input.path("include_disabled").asBoolean(false);

        var query = Wrappers.<User>lambdaQuery()
                .eq(User::getEnterpriseId, context.enterpriseId())
                .orderByAsc(User::getId)
                .last("limit " + MAX_ROWS);
        if (!includeDisabled) {
            query.eq(User::getStatus, User.Status.ACTIVE);
        }

        List<User> members = userMapper.selectList(query);
        if (members.isEmpty()) {
            return "当前企业没有符合条件的账号。";
        }

        StringBuilder sb = new StringBuilder("企业成员（共 ")
                .append(members.size())
                .append(" 个，最多显示 ")
                .append(MAX_ROWS)
                .append(" 个）：\n");
        for (User member : members) {
            sb.append("- ")
              .append(member.getUsername())
              .append(" | ")
              .append(nullSafe(member.getRealName()))
              .append(" | ")
              .append(member.getUserType() != null && member.getUserType() == User.Type.ENTERPRISE
                      ? "企业用户" : "平台账号")
              .append(" | ")
              .append(member.getStatus() != null && member.getStatus() == User.Status.ACTIVE
                      ? "启用" : "已禁用")
              .append('\n');
        }
        return sb.toString();
    }

    private String nullSafe(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
