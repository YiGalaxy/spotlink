package com.bulk.trade.shared.web;

import lombok.Getter;

/**
 * 应用的业务结果码。
 *
 * <p>编号分段，于是一个码本身就能说明它出自哪里：
 * <pre>
 *   0        成功
 *   1xxxx    通用／基础设施错误
 *   2xxxx    身份与访问错误
 *   3xxxx    商品与库存错误
 *   4xxxx    交易错误
 *   5xxxx    合同错误
 *   6xxxx    结算与资金错误
 *   7xxxx    交收错误
 * </pre>
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "OK"),

    // ---- 1xxxx 通用 ----
    BAD_REQUEST(10000, "请求参数有误"),
    UNAUTHORIZED(10001, "未登录或登录已过期"),
    FORBIDDEN(10002, "没有访问权限"),
    NOT_FOUND(10003, "资源不存在"),
    METHOD_NOT_ALLOWED(10004, "请求方法不支持"),
    CONFLICT(10005, "数据已存在或状态冲突"),
    TOO_MANY_REQUESTS(10006, "操作过于频繁，请稍后重试"),
    INTERNAL_ERROR(10007, "系统繁忙，请稍后重试"),

    // ---- 2xxxx 身份 ----
    LOGIN_FAILED(20000, "用户名或密码错误"),
    ACCOUNT_DISABLED(20001, "账号已被禁用"),
    ACCOUNT_LOCKED(20002, "账号已被锁定"),
    TOKEN_EXPIRED(20003, "登录已过期，请重新登录"),
    TOKEN_INVALID(20004, "登录凭证无效"),
    ENTERPRISE_NOT_APPROVED(20005, "企业尚未通过审核"),
    ENTERPRISE_FROZEN(20006, "企业账号已被冻结"),
    ENTERPRISE_NOT_FOUND(20007, "企业不存在"),
    USERNAME_TAKEN(20008, "用户名已被占用"),
    USCC_DUPLICATED(20009, "该统一社会信用代码已注册"),

    // ---- 3xxxx 商品 / 库存 ----
    COMMODITY_NOT_FOUND(30000, "商品不存在"),
    CATEGORY_NOT_FOUND(30001, "品类不存在"),
    CATEGORY_HAS_CHILDREN(30002, "该品类下存在子品类，无法删除"),
    INVENTORY_NOTE_NOT_FOUND(30003, "电子库存单不存在"),
    INVENTORY_NOTE_NOT_AVAILABLE(30004, "该库存单当前状态不可用"),
    INVENTORY_QUANTITY_INSUFFICIENT(30005, "库存数量不足"),

    // ---- 4xxxx 交易 ----
    LISTING_NOT_FOUND(40000, "挂牌不存在"),
    LISTING_EXPIRED(40001, "该挂牌已过期"),
    LISTING_ALREADY_CLOSED(40002, "该挂牌已关闭"),
    LISTING_NOT_OWNED(40003, "不能对自己发布的挂牌进行此操作"),
    LISTING_QUANTITY_EXCEEDED(40004, "摘牌数量超过挂牌剩余数量"),
    ORDER_NOT_FOUND(40005, "订单不存在"),
    ORDER_STATUS_INVALID(40006, "订单当前状态不允许此操作"),

    // ---- 5xxxx 合同 ----
    CONTRACT_NOT_FOUND(50000, "合同不存在"),
    CONTRACT_ALREADY_SIGNED(50001, "合同已签署，不能重复操作"),

    // ---- 6xxxx 结算 ----
    ACCOUNT_NOT_FOUND(60000, "资金账户不存在"),
    BALANCE_INSUFFICIENT(60001, "账户可用余额不足"),
    FREEZE_RECORD_NOT_FOUND(60002, "冻结记录不存在"),
    FREEZE_ALREADY_RELEASED(60003, "该冻结已解冻"),
    PAYMENT_AMOUNT_MISMATCH(60004, "支付金额与应付金额不一致"),

    // ---- 7xxxx 交收 ----
    DELIVERY_NOT_FOUND(70000, "交收单不存在"),
    DELIVERY_STATUS_INVALID(70001, "交收单当前状态不允许此操作"),
    WEIGHING_DEVIATION_EXCEEDED(70002, "磅差超出合同约定范围，需人工协商"),

    // ---- 8xxxx AI 顾问 ----
    ADVISOR_NOT_CONFIGURED(80000, "AI 顾问未配置，请先设置 API 密钥"),
    ADVISOR_DISABLED(80001, "AI 顾问未启用"),
    ADVISOR_TOOL_NOT_FOUND(80002, "AI 顾问调用了未注册的工具"),
    ADVISOR_TOOL_NOT_PERMITTED(80003, "AI 顾问无权调用该工具"),
    ADVISOR_ITERATION_LIMIT(80004, "AI 顾问处理步骤超出上限，请简化问题后重试"),
    ADVISOR_UNAVAILABLE(80005, "AI 服务暂时不可用，请稍后重试"),
    CONVERSATION_NOT_FOUND(80006, "会话不存在"),

    // ------------------------------------------------------------------
    // 9xxxx —— 运营后台
    // ------------------------------------------------------------------
    ADMIN_ENTERPRISE_NOT_FOUND(90000, "企业不存在"),
    ADMIN_ENTERPRISE_ALREADY_REVIEWED(90001, "该企业已审核过，无需重复处理"),
    ADMIN_REJECT_REASON_REQUIRED(90002, "驳回必须填写原因"),
    ADMIN_ENTERPRISE_NOT_APPROVED(90003, "只有已通过审核的企业才能冻结"),
    ADMIN_USER_NOT_FOUND(90004, "账号不存在"),
    ADMIN_SELF_OPERATION(90005, "不能对自己的账号执行此操作"),
    ADMIN_LAST_ADMIN(90006, "系统必须保留至少一个可分配角色的管理员"),
    ADMIN_ROLE_NOT_FOUND(90007, "角色不存在"),
    ADMIN_SYSTEM_ROLE_READONLY(90008, "系统内置角色不能修改或删除");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
