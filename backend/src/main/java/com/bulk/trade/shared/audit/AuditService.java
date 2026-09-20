package com.bulk.trade.shared.audit;

import com.bulk.trade.shared.audit.mapper.AuditLogMapper;
import com.bulk.trade.shared.security.LoginUser;
import com.bulk.trade.shared.security.SecurityUtils;
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
 * Records what operators did, so that what they did can be checked later.
 *
 * <p><b>Written after the transaction commits, in its own transaction.</b> Both
 * halves matter and they guard opposite failures:
 *
 * <ul>
 *   <li>Written <em>inside</em> the business transaction, a rolled-back approval
 *       would leave a row saying it happened. The audit would then be evidence
 *       of something that did not occur, which is worse than no audit at all —
 *       it is a false accusation with a timestamp.</li>
 *   <li>Written <em>in</em> that transaction, an audit failure would roll back a
 *       successful approval. A record-keeping problem must not be able to stop
 *       trading, so the insert does not join and cannot poison anything.</li>
 * </ul>
 *
 * <p>The result is the property worth having: a row exists exactly when the
 * change happened.
 *
 * <p>Called explicitly rather than through an aspect, because the fields worth
 * recording include the state <em>before</em> the change, and only the call site
 * knows it. An aspect could capture that the method ran; it could not capture
 * what the enterprise's status used to be.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /** Records a successful action. */
    public void record(String module, String action, String targetType, Long targetId,
                       Object before, Object after) {
        write(module, action, targetType, targetId, before, after, true, null, null);
    }

    /**
     * Records an action that was attempted and refused.
     *
     * <p>Refusals are worth keeping. A run of failed approvals from one account
     * is the shape of someone probing what they are allowed to do, and it is
     * invisible in any log that only records successes.
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
        // The tenant this row is *about*, which for a review is the enterprise
        // being reviewed rather than the operator's — an operator has none.
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
     * Defers the insert until the surrounding transaction has committed, or runs
     * it now when there is no transaction to wait for.
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
     * The insert itself, in a transaction of its own.
     *
     * <p><b>{@code TransactionTemplate} rather than {@code @Transactional}.</b>
     * Spring's annotation works through a proxy, and a call from one method of
     * this class to another does not go through it — so an annotated private or
     * self-invoked method runs with whatever transaction the caller has, which
     * here is none, and the annotation would be decoration. Calling the
     * template explicitly does what the annotation appears to promise.
     *
     * <p>Failures are logged and swallowed. A platform that refuses a
     * legitimate approval because it could not write a log line has its
     * priorities backwards.
     */
    private void insert(AuditLog row) {
        try {
            transactionTemplate.executeWithoutResult(status -> auditLogMapper.insert(row));
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
        // 512 in the schema; a truncated user agent is still useful, a failed
        // insert is not.
        row.setUserAgent(clip(request.getHeader("User-Agent"), 500));
    }

    /**
     * The caller's address, reading the forwarded header first.
     *
     * <p>Behind a proxy the socket address is the proxy's, which makes an audit
     * trail of who did what into a trail of which load balancer forwarded it.
     * The header is trivially forgeable and is trusted only because it is being
     * read for a record, never for an authorization decision.
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
            // A value that cannot be serialised is worth knowing about, but not
            // worth losing the audit row over.
            log.warn("Could not serialise an audit value of type {}", value.getClass().getName());
            return null;
        }
    }

    /** Column widths are finite; a long stack trace must not fail the insert. */
    private String clip(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit - 3) + "...";
    }
}
