package com.bulk.trade.shared.web;

import lombok.Getter;

/**
 * Application result codes.
 *
 * <p>Numbering scheme, so a code alone tells you where it came from:
 * <pre>
 *   0        success
 *   1xxxx    common / infrastructure errors
 *   2xxxx    identity and access errors
 *   3xxxx    commodity and inventory errors
 *   4xxxx    trading errors
 *   5xxxx    contract errors
 *   6xxxx    settlement and fund errors
 *   7xxxx    logistics errors
 * </pre>
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "OK"),

    // ---- 1xxxx common ----
    BAD_REQUEST(10000, "请求参数有误"),
    UNAUTHORIZED(10001, "未登录或登录已过期"),
    FORBIDDEN(10002, "没有访问权限"),
    NOT_FOUND(10003, "资源不存在"),
    METHOD_NOT_ALLOWED(10004, "请求方法不支持"),
    CONFLICT(10005, "数据已存在或状态冲突"),
    TOO_MANY_REQUESTS(10006, "操作过于频繁，请稍后重试"),
    INTERNAL_ERROR(10007, "系统繁忙，请稍后重试"),

    // ---- 2xxxx identity ----
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

    // ---- 3xxxx commodity / inventory ----
    COMMODITY_NOT_FOUND(30000, "商品不存在"),
    CATEGORY_NOT_FOUND(30001, "品类不存在"),
    CATEGORY_HAS_CHILDREN(30002, "该品类下存在子品类，无法删除"),
    INVENTORY_NOTE_NOT_FOUND(30003, "电子库存单不存在"),
    INVENTORY_NOTE_NOT_AVAILABLE(30004, "该库存单当前状态不可用"),
    INVENTORY_QUANTITY_INSUFFICIENT(30005, "库存数量不足"),

    // ---- 4xxxx trading ----
    LISTING_NOT_FOUND(40000, "挂牌不存在"),
    LISTING_EXPIRED(40001, "该挂牌已过期"),
    LISTING_ALREADY_CLOSED(40002, "该挂牌已关闭"),
    LISTING_NOT_OWNED(40003, "不能对自己发布的挂牌进行此操作"),
    LISTING_QUANTITY_EXCEEDED(40004, "摘牌数量超过挂牌剩余数量"),
    ORDER_NOT_FOUND(40005, "订单不存在"),
    ORDER_STATUS_INVALID(40006, "订单当前状态不允许此操作"),

    // ---- 5xxxx contract ----
    CONTRACT_NOT_FOUND(50000, "合同不存在"),
    CONTRACT_ALREADY_SIGNED(50001, "合同已签署，不能重复操作"),

    // ---- 6xxxx settlement ----
    ACCOUNT_NOT_FOUND(60000, "资金账户不存在"),
    BALANCE_INSUFFICIENT(60001, "账户可用余额不足"),
    FREEZE_RECORD_NOT_FOUND(60002, "冻结记录不存在"),
    FREEZE_ALREADY_RELEASED(60003, "该冻结已解冻"),
    PAYMENT_AMOUNT_MISMATCH(60004, "支付金额与应付金额不一致"),

    // ---- 7xxxx logistics ----
    DELIVERY_NOT_FOUND(70000, "交收单不存在"),
    DELIVERY_STATUS_INVALID(70001, "交收单当前状态不允许此操作"),
    WEIGHING_DEVIATION_EXCEEDED(70002, "磅差超出合同约定范围，需人工协商");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
