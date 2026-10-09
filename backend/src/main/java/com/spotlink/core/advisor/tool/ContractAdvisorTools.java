package com.spotlink.advisor.tool;

import com.spotlink.contract.entity.Contract;
import com.spotlink.contract.service.access.ContractAccess;
import com.spotlink.identity.entity.Enterprise;
import com.spotlink.identity.service.access.EnterpriseAccess;
import com.spotlink.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把合同交出来供审阅的工具。
 *
 * <p><b>工具负责取，模型负责审。</b>把规则检查直接写进 Java 很容易——「容差超过 5% 就是
 * 警告」不过是一个 if 语句。但那也会让审阅变成一份固定的清单，清单之外的东西一概注意
 * 不到，而这与审阅的目的正好相反。工具的职责是把文件与上下文交出去；判断，才是模型在
 * 这里的意义。
 *
 * <p>读取范围和系统里其他一切一样：只有调用方身为当事人的合同，按调用方自己的企业筛选。
 */
@Component
@RequiredArgsConstructor
public class ContractAdvisorTools {

    private final ContractAccess contractAccess;
    private final EnterpriseAccess enterpriseAccess;

    @Tool(name = "list_my_contracts",
            description = """
                    列出调用方作为当事人的合同：合同编号、对方、商品、金额和签署状态。
                    用来找用户想审的那份合同，或回答「我有哪些合同」。""")
    public String listMyContracts() {
        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业。";
        }

        List<Contract> contracts = contractAccess.findRecentParticipantContracts(enterpriseId, 20);

        if (contracts.isEmpty()) {
            return "当前企业还没有合同。";
        }

        StringBuilder sb = new StringBuilder("合同列表（最多显示 20 份）：\n");
        for (Contract contract : contracts) {
            sb.append("- ").append(contract.getContractNo())
              .append(" | ").append(contract.getTitle())
              .append(" | 金额 ").append(plain(contract.getAmount()))
              .append(" | 我方是").append(contract.isBuyer(enterpriseId) ? "买方" : "卖方")
              .append(" | ").append(Contract.Status.text(contract.getStatus()))
              .append('\n');
        }
        return sb.toString();
    }

    private String enterpriseName(Long id) {
        Enterprise enterprise = enterpriseAccess.selectById(id);
        return enterprise == null ? "—" : enterprise.getName();
    }

    private String plain(java.math.BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
