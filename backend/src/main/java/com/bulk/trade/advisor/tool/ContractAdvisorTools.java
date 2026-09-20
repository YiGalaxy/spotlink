package com.bulk.trade.advisor.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bulk.trade.contract.entity.Contract;
import com.bulk.trade.contract.mapper.ContractMapper;
import com.bulk.trade.identity.entity.Enterprise;
import com.bulk.trade.identity.mapper.EnterpriseMapper;
import com.bulk.trade.shared.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

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

    @Tool(name = "get_contract_detail",
            description = """
                    Returns the full text of one contract the caller is a party to: all terms
                    and both parties' signing status. Use it before reviewing a contract or
                    answering questions about its specific clauses. Requires the contract
                    number, which list_my_contracts provides.""")
    public String getContractDetail(
            @ToolParam(description = "Contract number, e.g. CT202609202025074559")
            String contractNo) {

        Long enterpriseId = SecurityUtils.currentEnterpriseIdOrNull();
        if (enterpriseId == null) {
            return "该账号是平台运营账号，未绑定企业。";
        }
        if (contractNo == null || contractNo.isBlank()) {
            return "请提供合同编号。可以先用 list_my_contracts 查看你有哪些合同。";
        }

        Contract contract = contractMapper.selectOne(Wrappers.<Contract>lambdaQuery()
                .eq(Contract::getContractNo, contractNo.trim()));
        if (contract == null || !contract.involves(enterpriseId)) {
            // Same answer for "does not exist" and "not yours": a distinct
            // message would confirm another company's contract number.
            return "没有找到该编号的合同，或你不是该合同的当事人。";
        }

        String buyerName = enterpriseName(contract.getBuyerId());
        String sellerName = enterpriseName(contract.getSellerId());

        return """
                合同编号: %s
                标题: %s
                买方: %s
                卖方: %s
                商品数量: %s %s
                单价: %s 元
                总金额: %s 元
                磅差容差: %s%%
                签署状态: %s（买方%s，卖方%s）

                条款正文:
                %s
                """.formatted(
                contract.getContractNo(),
                contract.getTitle(),
                buyerName,
                sellerName,
                plain(contract.getQuantity()), contract.getUnit(),
                plain(contract.getPrice()),
                plain(contract.getAmount()),
                contract.getWeightTolerance().stripTrailingZeros().toPlainString(),
                Contract.Status.text(contract.getStatus()),
                contract.getBuyerSignedAt() == null ? "未签" : "已签",
                contract.getSellerSignedAt() == null ? "未签" : "已签",
                prettyTerms(contract.getTerms()));
    }

    private String enterpriseName(Long id) {
        Enterprise enterprise = enterpriseMapper.selectById(id);
        return enterprise == null ? "—" : enterprise.getName();
    }

    /** Stored JSON is one line; a model reads a line-per-term form more reliably. */
    private String prettyTerms(String termsJson) {
        if (termsJson == null || termsJson.isBlank()) {
            return "（无条款正文）";
        }
        return termsJson.replace(",\"", ",\n\"").replace("{\"", "{\n\"")
                .lines()
                .map(String::trim)
                .collect(Collectors.joining("\n"));
    }

    private String plain(java.math.BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
