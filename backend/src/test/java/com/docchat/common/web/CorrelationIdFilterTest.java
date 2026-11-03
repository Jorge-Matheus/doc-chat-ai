package com.docchat.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesAnIdWhenTheHeaderIsMissing() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).hasSize(36);
    }

    @Test
    void reusesASafeIdFromTheClient() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "client-id-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME)).isEqualTo("client-id-12345");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "line\nbreak-injection", "<script>alert(1)</script>"})
    void replacesAnIdThatIsUnsafeToLog(String unsafeId) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, unsafeId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(CorrelationIdFilter.HEADER_NAME))
                .isNotEqualTo(unsafeId)
                .hasSize(36);
    }

    @Test
    void exposesTheIdInTheMdcOnlyWhileTheRequestRuns() throws Exception {
        AtomicReference<String> idDuringRequest = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                new MockHttpServletRequest(),
                response,
                (req, res) -> idDuringRequest.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(idDuringRequest.get()).isEqualTo(response.getHeader(CorrelationIdFilter.HEADER_NAME));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
