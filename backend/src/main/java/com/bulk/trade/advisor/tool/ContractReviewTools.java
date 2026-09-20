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
 * 读取一份合同的正文——而且只在调用方要求审查时，才交给模型。
 *
 * <p><b>为什么这是一个独立的 bean，而不是列表工具上的一个方法。</b>每一个工具结果都会
 * 进到提示词里，而提示词会发往一个外部模型服务商。这就使得<em>工具清单本身</em>成了决定
 * 「什么离开平台」的东西，而 Spring AI 是按 bean 来启用工具的——所以这项必须可选的
 * 能力，就只能是它自己的一个 bean。这个拆分是机械的，但拆分的理由不是：这里是整个代码
 * 库里唯一一处、按请求收窄「模型能读到什么」的地方。
 *
 * <p><b>被保护的是什么，不被保护的又是什么。</b>合同列表——编号、标题、金额、状态——始终
 * 可用，因为回答「我有哪些合同」是件寻常事，而这份索引里不含任何条款。等一个明确请求的，
 * 是一份法律文件的正文：当事人、价格、公差、结算依据、争议条款。**把一份合同的条款发给
 * 第三方，和发出「你有一份价值 204 万的合同」，是两个不同的动作**，它们不该共用一个开关。
 *
 * <p><b>这是最小化，不是访问控制。</b>合同属于调用者自己；这里判断错了在安全上一无所失，
 * 只意味着那份正文在本不需要的时候被发了出去。所以触发规则刻意做得简单，而它的失败方向
 * 两头都是安全的：漏触发会让助手请用户明说要审查，误触发则发出一份本就属于他的文档。
 */
@Component
@RequiredArgsConstructor
public class ContractReviewTools {

    private final ContractMapper contractMapper;
    private final EnterpriseMapper enterpriseMapper;

    @Tool(name = "get_contract_detail",
            description = """
                    返回调用方作为当事人的某份合同的全文：所有条款和双方的签署状态。
                    用来审阅一份合同，或回答关于它具体条款的问题。需要合同编号，
                    合同编号由 list_my_contracts 提供。

                    只有当用户要求审阅或解释某份合同时，这个工具才可用。如果它不在你的
                    工具集里而问题又需要条款原文，请让用户说明他想审阅这份合同——
                    不要猜测条款写了什么。""")
    public String getContractDetail(
            @ToolParam(description = "合同编号，例如 CT202609202025074559")
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
            // 「不存在」和「不是你的」给同一个回答：只要措辞有一点差别，
            // 这个差别本身就确认了另一家公司的合同编号存在。
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

    /** 存下来的 JSON 是一整行；模型读「一项一行」的形式更可靠。 */
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
