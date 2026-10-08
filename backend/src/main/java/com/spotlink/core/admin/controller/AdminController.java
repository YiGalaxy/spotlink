package com.spotlink.admin.controller;

import com.spotlink.admin.dto.AdminViews;
import com.spotlink.admin.service.AdminAuditService;
import com.spotlink.admin.service.AdminEnterpriseService;
import com.spotlink.admin.service.AdminOrderService;
import com.spotlink.admin.service.AdminOverviewService;
import com.spotlink.admin.service.AdminUserService;
import com.spotlink.shared.web.ApiResponse;
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
 * 运营控制台。
 *
 * <p><b>每个方法都点名声明自己所需的权限。</b>这里没有类级别的规则，也没有
 * "是不是运营人员"这种检查，因为角色只是一堆权限的集合，而去检验这个集合本身
 * 只会让集合里的内容沦为摆设。审计员持有读权限码、不持有任何写权限码，所以
 * "只读"是服务端强制出来的事实，而不是对某个角色名称的承诺。
 *
 * <p>这些接口全部位于 {@code /api/admin/} 之下，这里是唯一允许从请求参数中
 * 取得企业 ID 的前缀。该例外由两个测试来约束，而不是靠这段注释：一个测试断言
 * 本包中每个处理器都带有 {@code @PreAuthorize}，另一个断言本包之外的处理器
 * 一律不接受企业 ID。
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

    // ---------------------------------------------------------------- 概览

    @Operation(summary = "平台概览")
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('admin:overview')")
    public ApiResponse<AdminViews.Overview> overview() {
        return ApiResponse.success(overviewService.load());
    }

    // ---------------------------------------------------------------- 企业

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

    // ---------------------------------------------------------------- 订单

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

    // ---------------------------------------------------------------- 用户

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

    // ---------------------------------------------------------------- 审计

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

    // ---------------------------------------------------------------- 请求体

    /** approve、reject 与 freeze 三处共用：各自只读取其中一个字段，其余一概忽略。 */
    public record ReviewRequest(String traderCode, String reason) {
    }

    public record StatusRequest(Integer status, String reason) {
    }

    public record RolesRequest(List<Long> roleIds) {
    }
}
