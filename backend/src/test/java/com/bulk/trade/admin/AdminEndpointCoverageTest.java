package com.bulk.trade.admin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every console endpoint names the authority it requires.
 *
 * <p><b>This is what makes {@code .anyRequest().authenticated()} acceptable for
 * {@code /api/admin/**}.</b> The URL rule is a single line in SecurityConfig and
 * it says nothing about authority — a signed-in enterprise user passes it. So
 * the whole console's authorization rests on each handler carrying a
 * {@code @PreAuthorize}, and a handler that forgets one is reachable by any
 * member of the platform.
 *
 * <p>That failure is silent, plausible-looking, and exactly the kind of thing a
 * review catches once and a later commit undoes. Reflection does not forget:
 * this fails on the day someone adds an endpoint without a rule, which is the
 * day it should.
 *
 * <p>No database and no HTTP — it reads the handler mappings Spring built.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("运营后台：每个接口都必须声明权限")
class AdminEndpointCoverageTest {

    private static final String CONSOLE_PACKAGE = "com.bulk.trade.admin.controller";

    // Qualified by name: the actuator auto-configuration registers a second
    // RequestMappingHandlerMapping for its own endpoints, and an unqualified
    // injection finds two. The scan must see the application's mappings, not
    // the actuator's.
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("admin 包下的每个处理器都带 @PreAuthorize")
    void everyConsoleHandlerIsGuarded() {
        List<String> unguarded = new ArrayList<>();
        int checked = 0;

        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            if (!handler.getBeanType().getPackageName().equals(CONSOLE_PACKAGE)) {
                continue;
            }
            checked++;
            if (AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class) == null) {
                unguarded.add(handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName());
            }
        }

        assertThat(checked)
                .as("endpoints found under %s — a zero here means the scan broke, not that the console is small",
                        CONSOLE_PACKAGE)
                .isGreaterThan(0);
        assertThat(unguarded)
                .as("console endpoints reachable by any signed-in user")
                .isEmpty();
    }

    @Test
    @DisplayName("这些规则都指向真实存在的权限码，不是写错的字符串")
    void everyGuardNamesARealAuthority() {
        // A typo in an authority string fails closed — nobody holds
        // 'admin:enterpries:review' — so the endpoint becomes unreachable rather
        // than unprotected. That is the right direction to fail, and it is also
        // invisible: the screen simply never works. Caught here by asserting the
        // codes match the shape the catalogue uses.
        List<String> suspicious = new ArrayList<>();

        for (HandlerMethod handler : handlerMapping.getHandlerMethods().values()) {
            if (!handler.getBeanType().getPackageName().equals(CONSOLE_PACKAGE)) {
                continue;
            }
            PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(
                    handler.getMethod(), PreAuthorize.class);
            if (guard == null) {
                continue;
            }
            String expression = guard.value();
            if (!expression.startsWith("hasAuthority('admin:") || !expression.endsWith("')")) {
                suspicious.add(handler.getMethod().getName() + " -> " + expression);
            }
        }

        assertThat(suspicious)
                .as("guards that do not name an admin:* authority")
                .isEmpty();
    }
}
