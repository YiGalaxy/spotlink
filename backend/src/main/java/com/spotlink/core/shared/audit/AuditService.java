package com.spotlink.shared.audit;

import com.spotlink.shared.audit.service.access.AuditLogAccess;
import com.spotlink.shared.security.LoginUser;
import com.spotlink.shared.security.SecurityUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 记录运营人员的操作，以便日后可以核查他们做过什么。
 *
 * <p><b>在事务提交之后写入，并且使用自己的事务。</b>这两半都重要，而且各自防住的是相反的
 * 失败：
 *
 * <ul>
 *   <li>如果写在业务事务<em>内部</em>，一次回滚掉的审批会留下一条声称它发生过的记录。
 *       这样的审计就成了某件并未发生之事的证据，那比没有审计更糟 —— 那是一份带时间戳的
 *       诬告。</li>
 *   <li>如果写在那个事务<em>里</em>，一次审计写入失败会把一次成功的审批一起回滚。
 *       记录环节的问题绝不应该有能力让交易停下来，所以这条插入不加入该事务，也就毒不到
 *       任何东西。</li>
 * </ul>
 *
 * <p>结果正是那个值得拥有的性质：这一行存在，当且仅当该变更确实发生了。
 *
 * <p>显式调用而不是通过切面，因为值得记录的字段包含变更<em>之前</em>的状态，而只有调用点
 * 知道它。切面能捕捉到方法执行过；它捕捉不到该企业之前的状态是什么。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogAccess auditLogAccess;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /** 记录一次成功的操作。 */
    public void record(String module, String action, String targetType, Long targetId,
                       Object before, Object after) {
        write(module, action, targetType, targetId, before, after, true, null, null);
    }

    /**
     * 记录一次被尝试过并被拒绝的操作。
     *
     * <p>拒绝也是值得保留的。同一个账号连续出现一批失败的审批，正是有人在试探自己权限边界
     * 的样子，而它在任何只记录成功的日志里都看不见。
     */
    public void recordFailure(String module, String action, String targetType, Long targetId,
                              String errorMessage, Object before) {
        write(module, action, targetType, targetId, before, null, false, errorMessage, null);
    }

    // ------------------------------------------------------------------

    private void write(String module, String action, String targetType, Long targetId,
                       Object before, Object after, boolean success,
                       String errorMessage, Long costMs) {
        AuditLog row = new AuditLog();
        LoginUser user = SecurityUtils.currentUserOrNull();
        row.setUserId(user == null ? null : user.getUserId());
        row.setUsername(user == null ? null : user.getUsername());
        // 这一行所*关于*的那个租户；就一次审核而言，是被审核的企业而不是操作人所属的企业 ——
        // 操作人没有企业。
        row.setEnterpriseId(user == null ? null : user.getEnterpriseId());
        row.setModule(module);
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setBeforeData(toJson(before));
        row.setAfterData(toJson(after));
        row.setSuccess(success);
        row.setErrorMessage(clip(errorMessage, 1000));
        row.setCostMs(costMs);
        row.setCreatedAt(java.time.OffsetDateTime.now());
        fillRequestDetails(row);

        runAfterCommit(row);
    }

    /**
     * 把插入推迟到外层事务提交之后；没有事务可等时则立即执行。
     */
    private void runAfterCommit(AuditLog row) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    insert(row);
                }
            });
        } else {
            insert(row);
        }
    }

    /**
     * 插入本身，运行在它自己的事务里。
     *
     * <p><b>用 {@code TransactionTemplate} 而不是 {@code @Transactional}。</b>
     * Spring 的注解通过代理生效，而本类中一个方法调用另一个方法并不经过代理 —— 所以一个加了
     * 注解的私有方法或自调用方法，会运行在调用方当前所在的事务里，而这里调用方没有事务，那个
     * 注解就只是装饰。显式调用模板，才真正做到了注解表面上承诺的事。
     *
     * <p>失败会被记入日志并吞掉。一个因为写不下一行日志就拒绝一次合法审批的平台，
     * 优先级是反的。
     */
    private void insert(AuditLog row) {
        try {
            transactionTemplate.executeWithoutResult(status -> auditLogAccess.insert(row));
        } catch (Exception e) {
            log.error("Could not write an audit row for {}:{} on {}/{}",
                    row.getModule(), row.getAction(), row.getTargetType(), row.getTargetId(), e);
        }
    }

    private void fillRequestDetails(AuditLog row) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return;
        }
        HttpServletRequest request = attrs.getRequest();
        row.setIp(clientIp(request));
        // schema 里是 512；截断的 user agent 仍然有用，而插入失败没有用。
        row.setUserAgent(clip(request.getHeader("User-Agent"), 500));
    }

    /**
     * 调用方的地址，优先读取转发头。
     *
     * <p>在代理之后，套接字地址是代理的地址，这会把一份关于「谁做了什么」的审计轨迹，变成
     * 一份关于「哪个负载均衡转发了它」的轨迹。这个头极易伪造，之所以信任它，仅仅因为它被
     * 读取只是为留档，从不用于任何鉴权判断。
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return clip(comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim(), 60);
        }
        return clip(request.getRemoteAddr(), 60);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // 无法序列化的值值得知道，但不值得为它丢掉整条审计记录。
            log.warn("Could not serialise an audit value of type {}", value.getClass().getName());
            return null;
        }
    }

    /** 列宽是有限的；再长的堆栈也不能让这条插入失败。 */
    private String clip(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }
}
