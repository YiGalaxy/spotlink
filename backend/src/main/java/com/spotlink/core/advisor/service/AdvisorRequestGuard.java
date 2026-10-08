package com.spotlink.advisor.service;

import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.UUID;

/** Redis 原子限流和带所有权的请求锁，多个后端实例共用，崩溃后自动到期。 */
@Component
@RequiredArgsConstructor
public class AdvisorRequestGuard {
    private final StringRedisTemplate redis;
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local now = tonumber(redis.call('TIME')[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
            if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
            if redis.call('ZCARD', KEYS[3]) >= 4 then return 0 end
            local count = redis.call('INCR', KEYS[2])
            if count == 1 then redis.call('EXPIRE', KEYS[2], 60) end
            if count > 10 then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'EX', 3600)
            redis.call('ZADD', KEYS[3], now + 3600, ARGV[1])
            redis.call('EXPIRE', KEYS[3], 3600)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) end
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    public Lease acquire(Long userId) {
        String lock = "{advisor}:user:" + userId + ":busy";
        String token = UUID.randomUUID().toString();
        try {
            Long accepted = redis.execute(ACQUIRE, List.of(lock, "{advisor}:user:" + userId + ":rate", "{advisor}:active"), token);
            if (!Long.valueOf(1).equals(accepted)) throw BusinessException.of(ResultCode.TOO_MANY_REQUESTS,
                    "请求较多或上一条提问仍在处理中，请稍后再试");
            return new Lease(lock, token);
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { throw BusinessException.of(ResultCode.ADVISOR_UNAVAILABLE, "顾问服务暂时不可用，请稍后重试"); }
    }

    public final class Lease implements AutoCloseable {
        private final String lock;
        private final String token;
        private Lease(String lock, String token) { this.lock = lock; this.token = token; }
        @Override public void close() {
            try { redis.execute(RELEASE, List.of(lock, "{advisor}:active"), token); }
            catch (Exception ignored) { /* 不覆盖已完成回答或原始错误，锁有 TTL。 */ }
        }
    }
}
