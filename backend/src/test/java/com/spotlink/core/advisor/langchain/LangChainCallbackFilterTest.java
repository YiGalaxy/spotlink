package com.spotlink.advisor.langchain;

import com.spotlink.shared.exception.BusinessException;
import com.spotlink.shared.web.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LangChainCallbackFilterTest {
    @Test void unauthenticatedCallbackIsRejectedBeforeControllerOrBodyParsing() throws Exception {
        var gateway = mock(LangChainGateway.class);
        doThrow(BusinessException.of(ResultCode.ADVISOR_TOOL_NOT_PERMITTED)).when(gateway).authorizeCallback(null);
        var request = new MockHttpServletRequest("POST", "/internal/advisor/langchain/tools");
        request.setContent("invalid-json".getBytes());
        var response = new MockHttpServletResponse();
        new LangChainCallbackFilter(gateway).doFilter(request, response, (req, res) -> fail("controller reached"));
        assertThat(response.getStatus()).isEqualTo(403);
    }
    @Test void oversizedBodyIsRejectedEvenWithoutTrustingDeclaredLength() throws Exception {
        var request = new MockHttpServletRequest("POST", "/internal/advisor/langchain/v1/chat/completions");
        request.addHeader("Authorization", "Bearer delegation");
        request.setContent(new byte[65537]);
        var response = new MockHttpServletResponse();
        new LangChainCallbackFilter(mock(LangChainGateway.class)).doFilter(request, response, (req, res) -> fail("controller reached"));
        assertThat(response.getStatus()).isEqualTo(413);
    }
    @Test void allowedBodyCanBeReadByDownstreamAndOnlyPublicKeyGetBypassesDelegation() throws Exception {
        var gateway = mock(LangChainGateway.class);
        var filter = new LangChainCallbackFilter(gateway);
        var request = new MockHttpServletRequest("POST", "/internal/advisor/langchain/tools");
        request.addHeader("Authorization", "Bearer delegation"); request.setContent("{}".getBytes());
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> assertThat(req.getReader().readLine()).isEqualTo("{}"));
        verify(gateway).authorizeCallback("Bearer delegation");
        var calls = new AtomicInteger();
        filter.doFilter(new MockHttpServletRequest("GET", "/internal/advisor/langchain/key"), new MockHttpServletResponse(), (req, res) -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(1);
        verifyNoMoreInteractions(gateway);
    }
}
