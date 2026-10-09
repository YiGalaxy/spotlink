package com.spotlink.advisor.tool;

import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.entity.User;
import com.spotlink.identity.service.access.EnterpriseAccess;
import com.spotlink.identity.service.access.UserAccess;
import com.spotlink.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 顾问可以调用的工具。
 *
 * <p>这些都是普通 Spring Bean 上的普通方法 —— 依赖会被注入，调用方的身份可以从安全上下文
 * 中拿到，正是这一点让下面那条租户规则是可执行的，而不是一句空想。
 *
 * <p><b>租户永远不作为参数传入。</b>每个方法都从 {@link SecurityUtils} 读取企业，所以模型
 * 没有任何办法点名一家公司。如果先接受一个 id、事后再校验，安全性就要依赖未来每一个工具
 * 作者都记得去校验 —— 而漏掉一次检查就是一次越权。第二个理由是提示词注入：模型读到的文本
 * （用户消息、文档、工具结果）否则就能指使去查另一个租户。
 *
 * <p>description 是写给模型看的，不是写给人类读者的：它们说明何时该调用该工具，
 * 以及同样重要的 —— 它做不到什么。
 */
@Component
@RequiredArgsConstructor
public class AdvisorTools {

    /** 工具结果会作为输入 token 再次发送；要控制体积。 */
    private static final int MAX_MEMBERS = 50;

    private final EnterpriseAccess enterpriseAccess;
    private final UserAccess userAccess;

    @Tool(name = "query_my_enterprise",
            description = """
                    返回调用方**自己企业**的资料：公司名称、交易席位编码、审核状态和联系方式。
                    不接受参数，永远指调用方自己的公司。用于用户问自己的账号、公司信息，
                    或注册与审核状态。""")
    public String queryMyEnterprise() {
        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业。";
        }

        Enterprise enterprise = enterpriseAccess.selectById(enterpriseId);
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
                    列出调用方**自己企业**下的账号：用户名、姓名、账号类型和启用/停用状态。
                    用于公司成员、子账号、谁有权限这类问题。它无法返回别的企业的成员。""")
    public String queryTeamMembers(
            @ToolParam(description = "包含已停用的账号。默认 false。")
            Boolean includeDisabled) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业，因此没有企业成员列表。";
        }

        boolean withDisabled = Boolean.TRUE.equals(includeDisabled);

        List<User> members = userAccess.findEnterpriseMembers(enterpriseId, withDisabled, MAX_MEMBERS);
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
