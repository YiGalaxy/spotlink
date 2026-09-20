package com.bulk.trade.admin.controller;

import com.bulk.trade.admin.dto.AdminViews;
import com.bulk.trade.admin.service.AdminAuditService;
import com.bulk.trade.admin.service.AdminEnterpriseService;
import com.bulk.trade.admin.service.AdminOrderService;
import com.bulk.trade.admin.service.AdminOverviewService;
import com.bulk.trade.admin.service.AdminUserService;
import com.bulk.trade.shared.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The operator console.
 *
 * <p><b>Every method names the authority it requires.</b> There is no
 * class-level rule and no "is an operator" check, because a role is a bag of
 * authorities and testing the bag would make its contents decorative. An
 * auditor holds the read codes and none of the write ones, so read-only is a
 * fact the server enforces rather than a promise about a role's name.
 *
 * <p>All of it sits under {@code /api/admin/}, which is the one prefix where an
 * enterprise id may come from a request parameter. That exception is bounded by
 * two tests rather than by this comment: one asserts every handler in this
 * package carries a {@code @PreAuthorize}, and one asserts no handler outside
 * it accepts an enterprise id at all.
 */
@Tag(name = "运营后台", description = "平台侧管理：概览、企业审核、订单查询、用户权限、审计日志")
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AdminOverviewService overviewService;
    private final AdminEnterpriseService enterpriseService;
    private final AdminOrderService orderService;
    private final AdminUserService userService;
    private final AdminAuditService auditService;

    // ---------------------------------------------------------------- overview

    @Operation(summary = "平台概览")
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('admin:overview')")
    public ApiResponse<AdminViews.Overview> overview() {
        return ApiResponse.success(overviewService.load());
    }

    // ---------------------------------------------------------------- enterprises

    @Operation(summary = "企业列表", description = "可按状态与关键词筛选")
    @GetMapping("/enterprises")
    @PreAuthorize("hasAuthority('admin:enterprise')")
    public ApiResponse<List<AdminViews.EnterpriseRow>> enterprises(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(enterpriseService.search(status, keyword));
    }

    @Operation(summary = "通过审核", description = "写入审核时间与审核人；未分配席位则同时分配")
    @PostMapping("/enterprises/{id}/approve")
    @PreAuthorize("hasAuthority('admin:enterprise:review')")
    public ApiResponse<AdminViews.EnterpriseRow> approve(
            @PathVariable Long id,
            @RequestBody(required = false) ReviewRequest request) {
        return ApiResponse.success(enterpriseService.approve(
                id, request == null ? null : request.traderCode()));
    }

    @Operation(summary = "驳回申请", description = "必须填写原因，申请方会看到它")
    @PostMapping("/enterprises/{id}/reject")
    @PreAuthorize("hasAuthority('admin:enterprise:review')")
    public ApiResponse<AdminViews.EnterpriseRow> reject(
            @PathVariable Long id,
            @RequestBody(required = false) ReviewRequest request) {
        return ApiResponse.success(enterpriseService.reject(
                id, request == null ? null : request.reason()));
    }

    @Operation(summary = "冻结企业", description = "冻结后其账号在下次请求即被拒绝，不必等令牌过期")
    @PostMapping("/enterprises/{id}/freeze")
    @PreAuthorize("hasAuthority('admin:enterprise:freeze')")
    public ApiResponse<AdminViews.EnterpriseRow> freeze(
            @PathVariable Long id,
            @RequestBody(required = false) ReviewRequest request) {
        return ApiResponse.success(enterpriseService.freeze(
                id, request == null ? null : request.reason()));
    }

    @Operation(summary = "解冻企业")
    @PostMapping("/enterprises/{id}/unfreeze")
    @PreAuthorize("hasAuthority('admin:enterprise:freeze')")
    public ApiResponse<AdminViews.EnterpriseRow> unfreeze(@PathVariable Long id) {
        return ApiResponse.success(enterpriseService.unfreeze(id));
    }

    // ---------------------------------------------------------------- orders

    @Operation(summary = "订单查询",
            description = "跨全部企业的订单查询。enterpriseId 是全平台唯一允许从请求参数读取企业的地方，"
                    + "由 admin:order 权限守护；其余接口的企业 ID 一律来自登录态。")
    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('admin:order')")
    public ApiResponse<List<AdminViews.OrderRow>> orders(
            @RequestParam(required = false) Long enterpriseId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String orderNo,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(orderService.search(enterpriseId, status, orderNo, limit));
    }

    @Operation(summary = "有订单往来的企业", description = "供订单页的企业筛选项使用")
    @GetMapping("/orders/parties")
    @PreAuthorize("hasAuthority('admin:order')")
    public ApiResponse<List<AdminViews.EnterpriseRow>> orderParties() {
        return ApiResponse.success(orderService.orderParties());
    }

    // ---------------------------------------------------------------- users

    @Operation(summary = "账号列表")
    @GetMapping("/users")
    @PreAuthorize("hasAuthority('admin:user')")
    public ApiResponse<List<AdminViews.UserRow>> users(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return ApiResponse.success(userService.search(keyword, status));
    }

    @Operation(summary = "启用/禁用账号", description = "立即生效：下一次请求即被拒绝，不必等令牌过期")
    @PostMapping("/users/{id}/status")
    @PreAuthorize("hasAuthority('admin:user:status')")
    public ApiResponse<AdminViews.UserRow> changeUserStatus(
            @PathVariable Long id,
            @RequestBody StatusRequest request) {
        return ApiResponse.success(userService.changeStatus(id, request.status(), request.reason()));
    }

    @Operation(summary = "分配角色")
    @PostMapping("/users/{id}/roles")
    @PreAuthorize("hasAuthority('admin:user:role')")
    public ApiResponse<AdminViews.UserRow> assignRoles(
            @PathVariable Long id,
            @RequestBody RolesRequest request) {
        return ApiResponse.success(userService.assignRoles(id, request.roleIds()));
    }

    @Operation(summary = "角色列表", description = "含每个角色持有的权限码")
    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('admin:user')")
    public ApiResponse<List<AdminViews.RoleRow>> roles() {
        return ApiResponse.success(userService.roles());
    }

    @Operation(summary = "可授予的权限", description = "供角色编辑页渲染勾选列表")
    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('admin:user')")
    public ApiResponse<List<AdminViews.PermissionRow>> permissions() {
        return ApiResponse.success(userService.permissions());
    }

    // ---------------------------------------------------------------- audit

    @Operation(summary = "审计日志", description = "写操作留痕，倒序；只记录写操作，不记录读取")
    @GetMapping("/audit-logs")
    @PreAuthorize("hasAuthority('admin:audit')")
    public ApiResponse<List<AdminViews.AuditRow>> auditLogs(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Boolean success,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(auditService.search(module, action, username, success, limit));
    }

    // ---------------------------------------------------------------- payloads

    /** Shared by approve, reject and freeze: each reads one field and ignores the rest. */
    public record ReviewRequest(String traderCode, String reason) {
    }

    public record StatusRequest(Integer status, String reason) {
    }

    public record RolesRequest(List<Long> roleIds) {
    }
}
