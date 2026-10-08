package com.spotlink.advisor.agent;

import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import java.text.Normalizer;
import java.util.regex.Pattern;

/** 快速拒绝明显滥用；权限安全依赖受限工具，而非关键词命中。 */
public final class AdvisorInputPolicy {
    private AdvisorInputPolicy() {}
    private static final Pattern ATTACK = Pattern.compile("(?is)(忽略|绕过|覆盖).{0,20}(系统|之前|规则|限制|指令)|ignore.{0,30}(previous|system|instructions)|"
            + "(输出|泄露|打印|告诉|读取|显示).{0,24}(api.?key|密钥|系统提示词|数据库密码)|"
            + "(执行|运行).{0,20}(sql|shell|命令|删库)|drop\\s+table|union\\s+select|select\\s+\\*\\s+from");
    public static String normalize(String value) {
        if (value == null || value.length() > 4000) throw BusinessException.of(ResultCode.BAD_REQUEST, "提问内容不能为空且不能超过4000个字符");
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("[\\p{Cf}]", "").strip();
        if (text.isBlank() || text.length() > 4000) throw BusinessException.of(ResultCode.BAD_REQUEST, "请输入4000个字符以内的有效问题");
        if (text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
            throw BusinessException.of(ResultCode.BAD_REQUEST, "提问包含不支持的控制字符");
        return text;
    }
    public static String localReply(String text) {
        if (text.matches("(?i)^(你好|您好|嗨|hi|hello)[!！。,.\\s]*$")) return "你好，可以直接告诉我商品、数量和交付地点。我能查询货物、比较报价，并提供真实挂牌入口。";
        if (text.matches("^(谢谢|感谢|谢谢你)[!！。,.\\s]*$")) return "不客气。需要继续比较货物或查询交易进度时，直接提问即可。";
        if (ATTACK.matcher(text).find()) return "我可以查询公开挂牌和你本企业的业务记录，但不能提供密钥、系统内部信息，或执行数据库及后台操作。请说明需要查询的货物或交易问题。";
        if (!text.codePoints().anyMatch(Character::isLetterOrDigit) || Pattern.compile("(.)\\1{100,}", Pattern.DOTALL).matcher(text).find())
            return "请用一句话说明货物或交易需求，例如：查询上海交收的电解铜，比较单价和余量。";
        return null;
    }
}
