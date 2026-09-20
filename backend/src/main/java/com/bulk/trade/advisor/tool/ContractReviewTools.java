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

import java.math.BigDecimal;
import java.util.stream.Collectors;

/**
 * Reads the full text of a contract — and is only handed to the model when the
 * caller has asked for a review.
 *
 * <p><b>Why this is a separate bean rather than a method on the list tools.</b>
 * Every tool result goes into the prompt and the prompt goes to an external
 * model provider. That makes the <em>tool list itself</em> the thing that
 * decides what leaves the platform, and Spring AI enables tools per bean — so
 * the capability that must be optional has to be its own bean. The split is
 * mechanical, but the reason for it is not: it is the only place in this
 * codebase where what the model may read is narrowed per request.
 *
 * <p><b>What is being protected, and what is not.</b> Listing contracts —
 * number, title, amount, status — stays available always, because answering
 * "我有哪些合同" is ordinary and the index carries no clauses. What waits for an
 * explicit request is the text of a legal document: parties, price, tolerance,
 * settlement basis, dispute terms. Sending a contract's terms to a third party
 * is a different act from sending "you have one contract worth 2,040,000", and
 * the two should not share a switch.
 *
 * <p><b>This is minimisation, not access control.</b> The contract belongs to
 * the caller; a wrong guess here costs nothing in security and simply means the
 * text was sent when it need not have been. So the trigger is deliberately
 * simple and the failure mode is safe in both directions: a missed trigger
 * prompts the user to ask for a review, and a spurious one sends a document
 * they already own.
 */
@Component
@RequiredArgsConstructor
public class ContractReviewTools {

    private final ContractMapper contractMapper;
    private final EnterpriseMapper enterpriseMapper;

    @Tool(name = "get_contract_detail",
            description = """
                    Returns the full text of one contract the caller is a party to: all terms
                    and both parties' signing status. Use it to review a contract or to answer
                    questions about its specific clauses. Requires the contract number, which
                    list_my_contracts provides.

                    This tool is only available when the user has asked for a contract to be
                    reviewed or explained. If it is not in your toolset and the question needs
                    the clause text, ask the user to say they want the contract reviewed —
                    do not guess at what the terms say.""")
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
                enterpriseName(contract.getBuyerId()),
                enterpriseName(contract.getSellerId()),
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

    private String plain(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }
}
