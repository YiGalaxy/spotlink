package com.bulk.trade.advisor.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.contract.entity.Contract;
import com.bulk.trade.contract.mapper.ContractMapper;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Tools that expose contracts for review.
 *
 * <p><b>The tool fetches; the model reviews.</b> It would be easy to put the
 * rule-checking in Java — "tolerance above 5% is a warning" is an if-statement.
 * That would also make the review a fixed checklist that cannot notice anything
 * outside it, which is the opposite of what a review is for. The tool's job is
 * to hand over the document and the context; judgement is what the model is
 * there for.
 *
 * <p>Reading is scoped the same way everything else is: only contracts the
 * caller is a party to, selected by the caller's own enterprise.
 */
@Component
@RequiredArgsConstructor
public class ContractAdvisorTools {

    private final ContractMapper contractMapper;
    private final EnterpriseMapper enterpriseMapper;

    @Tool(name = "list_my_contracts",
            description = """
                    Lists contracts the caller is a party to: contract number, counterparty,
                    commodity, amount and signing status. Use it to find the contract a user
                    wants reviewed, or to answer "我有哪些合同".""")
    public String listMyContracts() {
        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业。";
        }

        List<Contract> contracts = contractMapper.selectList(Wrappers.<Contract>lambdaQuery()
                .and(w -> w.eq(Contract::getBuyerId, enterpriseId)
                        .or()
                        .eq(Contract::getSellerId, enterpriseId))
                .orderByDesc(Contract::getId)
                .last("limit 20"));

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
        Enterprise enterprise = enterpriseMapper.selectById(id);
        return enterprise == null ? "—" : enterprise.getName();
    }

    private String plain(java.math.BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
