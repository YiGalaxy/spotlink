package com.bulk.trade.advisor.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.entity.User;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.identity.mapper.UserMapper;
import com.bulk.trade.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tools the advisor may call.
 *
 * <p>These are ordinary methods on an ordinary Spring bean — dependencies are
 * injected and the caller's identity is available from the security context,
 * which is what makes the tenant rule below enforceable rather than aspirational.
 *
 * <p><b>The tenant is never a parameter.</b> Every method reads the enterprise
 * from {@link SecurityUtils}, so the model has no way to name a company. If an
 * id were accepted and validated afterwards, safety would depend on each future
 * tool author remembering to validate — and one forgotten check is a breach.
 * A second reason is prompt injection: text the model reads (a user message, a
 * document, a tool result) could otherwise instruct it to look up another
 * tenant.
 *
 * <p>Descriptions are written for the model, not for a human reader: they say
 * when to call the tool and, just as importantly, what it cannot do.
 */
@Component
@RequiredArgsConstructor
public class AdvisorTools {

    /** Tool results are re-sent as input tokens; keep them small. */
    private static final int MAX_MEMBERS = 50;

    private final EnterpriseMapper enterpriseMapper;
    private final UserMapper userMapper;

    @Tool(name = "query_my_enterprise",
            description = """
                    Returns the profile of the caller's OWN enterprise: company name, trading
                    seat code, review status and contact details. Takes no arguments and always
                    refers to the caller's own company. Use it for questions about the user's
                    own account, company information, or registration/review status.""")
    public String queryMyEnterprise() {
        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业。";
        }

        Enterprise enterprise = enterpriseMapper.selectById(enterpriseId);
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
                orDash(enterprise.getShortName()),
                enterprise.getEnterpriseCode(),
                orDash(enterprise.getTraderCode(), "尚未分配"),
                statusText(enterprise.getStatus()),
                orDash(enterprise.getLegalPerson()),
                orDash(enterprise.getContactName()),
                orDash(enterprise.getContactPhone()),
                orDash(enterprise.getProvince()),
                orDash(enterprise.getCity()));
    }

    @Tool(name = "query_team_members",
            description = """
                    Lists the accounts under the caller's OWN enterprise: username, real name,
                    account type and enabled/disabled status. Use it for questions about company
                    members, sub-accounts, or who has access. It cannot return another company's
                    members.""")
    public String queryTeamMembers(
            @ToolParam(description = "Include disabled accounts. Defaults to false.")
            Boolean includeDisabled) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，因此没有企业成员列表。";
        }

        boolean withDisabled = Boolean.TRUE.equals(includeDisabled);

        var query = Wrappers.<User>lambdaQuery()
                .eq(User::getEnterpriseId, enterpriseId)
                .orderByAsc(User::getId)
                .last("limit " + MAX_MEMBERS);
        if (!withDisabled) {
            query.eq(User::getStatus, User.Status.ACTIVE);
        }

        List<User> members = userMapper.selectList(query);
        if (members.isEmpty()) {
            return "当前企业没有符合条件的账号。";
        }

        StringBuilder sb = new StringBuilder("企业成员（共 ")
                .append(members.size())
                .append(" 个，最多显示 ")
                .append(MAX_MEMBERS)
                .append(" 个）：\n");
        for (User member : members) {
            sb.append("- ")
              .append(member.getUsername())
              .append(" | ")
              .append(orDash(member.getRealName()))
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

    private String orDash(String value) {
        return orDash(value, "—");
    }

    private String orDash(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
