package com.bulk.trade.advisor.agent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Decides whether this turn is allowed to read a contract's full text.
 *
 * <p><b>Why a rule and not the model.</b> Letting the model decide would put
 * the decision inside the thing being constrained: a model that can call the
 * tool will call it whenever it seems useful, and "seems useful" is exactly the
 * judgement that puts a contract's clauses into an external provider's logs
 * without anyone having asked for a review. The rule runs in Java, before the
 * prompt is built, where it can be read and tested.
 *
 * <p><b>Why a crude rule is acceptable here.</b> This is minimisation, not
 * access control. The contract belongs to the caller, so a wrong answer costs
 * nothing in security — it only changes whether a document they own was sent
 * when it need not have been. Both failure modes are cheap and both are
 * recoverable: a missed trigger means the assistant asks the user to say what
 * they want, and a spurious one sends a document the user was already asking
 * about. A rule that is easy to read beats a classifier that is hard to
 * justify, for a decision with this little at stake.
 *
 * <p><b>Both halves are required.</b> A review verb alone would fire on
 * "我的库存有没有问题"; a contract noun alone would fire on "我有哪些合同", which
 * is a listing question the index tools already answer. Requiring both is what
 * keeps the narrow case narrow.
 */
public final class ContractReviewTrigger {

    /** Naming a contract — by word or by number. */
    private static final Pattern MENTIONS_CONTRACT =
            Pattern.compile("合同|合约|契约|CT\\d{6,}");

    /**
     * Asking for it to be examined.
     *
     * <p>"条款" and "问题" are here because they are how the request is
     * actually phrased — "这份合同的条款有问题吗" contains no verb from the first
     * group, and requiring one would miss the question this feature exists for.
     */
    private static final List<String> REVIEW_INTENT = List.of(
            "审查", "审核", "审阅", "帮我看看", "帮我查查", "帮我审",
            "把关", "风险", "条款", "问题", "有没有坑", "review");

    private ContractReviewTrigger() {
    }

    /**
     * True when the message asks for a contract to be read closely.
     *
     * <p>Matched against the user's own words only — never against tool output
     * or a retrieved document, both of which are data that must not be able to
     * widen what the assistant may read. That distinction is the difference
     * between a trigger and a prompt-injection target.
     */
    public static boolean requested(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        String message = userMessage.toLowerCase();
        if (!MENTIONS_CONTRACT.matcher(userMessage).find()) {
            return false;
        }
        for (String intent : REVIEW_INTENT) {
            if (message.contains(intent)) {
                return true;
            }
        }
        return false;
    }
}
