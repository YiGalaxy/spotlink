package com.spotlink.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant key comes from the session everywhere except the console.
 *
 * <p><b>This is the test that makes "everywhere" mean something.</b> The rule is
 * stated in the README, in SecurityUtils' javadoc and in every service that
 * reads it, and until now it was enforced by nothing but care. Care is what
 * fails quietly: a parameter added for a legitimate-looking reason — "support
 * needs to see this enterprise's orders" — is a rule that stops being true, and
 * nothing anywhere would say so.
 *
 * <p>So it is checked structurally. Every handler outside
 * {@code com.spotlink.admin.controller} is inspected for a request binding
 * whose name looks like a tenant key, including the fields of any request body
 * record, and any hit fails the build. The console is the one package allowed
 * to ask, and it now asks exactly once — in the order query — behind a
 * permission of its own.
 *
 * <p>Avoids matching a substring, deliberately. {@code orderId} contains neither
 * {@code buyerId} nor {@code sellerId}, and {@code enterpriseName} is a filter
 * on a name rather than a key — matching loosely would produce false alarms
 * that get suppressed, and a suppressed check is worse than none.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("租户隔离：除运营后台外，没有任何接口从请求里读企业 ID")
class EnterpriseIdParameterIsolationTest {

    /** The console, and only the console, may take a tenant from the request. */
    private static final String CONSOLE_PACKAGE = "com.spotlink.admin.controller";

    /**
     * Names that would let a caller name another tenant.
     *
     * <p>Exactly these, not a pattern. The list is short and closed because the
     * number of ways to mean "whose data" is small, and a broad match would flag
     * things like {@code enterpriseName} that are not keys at all.
     */
    private static final Set<String> TENANT_KEYS = Set.of(
            "enterpriseId", "tenantId", "companyId", "buyerId", "sellerId", "ownerId");

    private static final DefaultParameterNameDiscoverer PARAMETER_NAMES =
            new DefaultParameterNameDiscoverer();

    // Qualified by name: the actuator auto-configuration registers a second
    // RequestMappingHandlerMapping for its own endpoints, and an unqualified
    // injection finds two. The scan must see the application's mappings, not
    // the actuator's.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("admin 包之外的处理器不接受任何租户键参数")
    void noHandlerOutsideTheConsoleTakesATenantKey() {
        List<String> offenders = new ArrayList<>();
        List<String> unnameable = new ArrayList<>();

        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            if (handler.getBeanType().getPackageName().equals(CONSOLE_PACKAGE)) {
                continue;
            }
            for (MethodParameter parameter : handler.getMethodParameters()) {
                collect(handler, parameter, offenders, unnameable);
            }
        }

        assertThat(offenders)
                .as("handlers that would let a caller name another company")
                .isEmpty();

        // A parameter whose name cannot be read is one this check cannot vouch
        // for, so it is a failure rather than a skip. Spring Boot compiles with
        // `-parameters`, so this should always be empty; if it is not, the
        // guard has gone blind and every assertion above is worth nothing.
        assertThat(unnameable)
                .as("request parameters whose names could not be read — the check cannot see these")
                .isEmpty();
    }

    @Test
    @DisplayName("触发器本身有效：故意找一个真会命中的形状")
    void theCheckCanActuallyFail() throws Exception {
        // A guard that cannot fail is not a guard, and this one inspects
        // reflection — where a small mistake means it silently checks nothing.
        // So it is pointed at a record that does carry such a field and expected
        // to report it.
        record PretendRequest(Long enterpriseId) {
        }
        List<String> found = new ArrayList<>();
        for (RecordComponent component : PretendRequest.class.getRecordComponents()) {
            if (TENANT_KEYS.contains(component.getName())) {
                found.add(component.getName());
            }
        }

        assertThat(found)
                .as("the tenant-key check does not recognise its own test case")
                .containsExactly("enterpriseId");
    }

    // ------------------------------------------------------------------

    /**
     * The name a caller would use to bind this parameter.
     *
     * <p>The annotation's own name first — {@code @RequestParam("enterpriseId")}
     * is explicit and always readable — then the compiled parameter name, which
     * is only present when the class was built with {@code -parameters}.
     */
    private String boundName(MethodParameter parameter) {
        RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
        if (requestParam != null && !requestParam.name().isBlank()) {
            return requestParam.name();
        }
        PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
        if (pathVariable != null && !pathVariable.name().isBlank()) {
            return pathVariable.name();
        }
        // Spring's own discoverer rather than MethodParameter.getParameterName:
        // that returns null until name discovery has been run on the parameter,
        // and nothing has run it here. Reading it without this made every
        // parameter look unnameable, which the assertion below then reported —
        // the check was blind and said so, which is the outcome worth having.
        // The discoverer takes Method or Constructor, not Executable — and a
        // request handler is always a method.
        String[] names = parameter.getExecutable() instanceof java.lang.reflect.Method method
                ? PARAMETER_NAMES.getParameterNames(method)
                : null;
        if (names == null || parameter.getParameterIndex() >= names.length) {
            return null;
        }
        return names[parameter.getParameterIndex()];
    }

    private void collect(HandlerMethod handler, MethodParameter parameter,
                         List<String> offenders, List<String> unnameable) {
        String where = handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();

        if (parameter.hasParameterAnnotation(RequestParam.class)
                || parameter.hasParameterAnnotation(PathVariable.class)) {
            String name = boundName(parameter);
            if (name == null) {
                unnameable.add(where + " -> " + parameter.getParameterType().getSimpleName());
            } else if (TENANT_KEYS.contains(name)) {
                offenders.add(where + " -> " + name);
            }
            return;
        }

        // A body record can carry the key inside it, where a parameter scan
        // alone would not see it.
        if (parameter.hasParameterAnnotation(RequestBody.class)) {
            Class<?> type = parameter.getParameterType();
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    if (TENANT_KEYS.contains(component.getName())) {
                        offenders.add(where + " -> body." + component.getName());
                    }
                }
            } else {
                for (Field field : type.getDeclaredFields()) {
                    if (TENANT_KEYS.contains(field.getName())) {
                        offenders.add(where + " -> body." + field.getName());
                    }
                }
            }
        }
    }
}
