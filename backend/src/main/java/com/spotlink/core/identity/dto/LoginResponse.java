package com.spotlink.identity.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.util.List;

/**
 * 签发的凭证，加上前端渲染界面外壳所需的账号资料。
 *
 * <p>密码哈希从不属于这个载体的内容——mapper 会把它查出来，但没有任何东西把它传出
 * service 层。
 *
 * <p><b>ID 序列化为字符串。</b>雪花 ID 是 19 位，而 JavaScript 的数字超过 16 位就会
 * 丢精度——{@code 2101635756223557634} 到达浏览器时会变成 {@code 2101635756223557600}。
 * 用 JSON 数字传输，会让客户端回传的每一个 id 都变成另一个 id，症状就是用户刚发起的
 * 请求报「记录不存在」。字符串能原样走完这个往返。
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        long expiresInSeconds,
        UserProfile user
) {

    public record UserProfile(
            @JsonSerialize(using = ToStringSerializer.class) Long userId,
            String username,
            String realName,
            @JsonSerialize(using = ToStringSerializer.class) Long enterpriseId,
            String enterpriseName,
            String traderCode,
            Integer userType,
            boolean platformOperator,

            /**
             * 该账号持有的权限码，例如 admin:enterprise:review。
             *
             * <p>发出来是为了让运营后台只渲染调用方用得上的东西。那是便利，不是边界——
             * 每一条都会在服务端再校验一次；一个凭空捏造权限码的客户端会发现，
             * 它画出来的那个按钮返回 403。
             */
            List<String> permissions,

            /** 角色码，用于展示。权限才管授权。 */
            List<String> roles
    ) {
    }
}
