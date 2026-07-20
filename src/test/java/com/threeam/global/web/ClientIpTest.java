package com.threeam.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.filters.RemoteIpFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockFilterConfig;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

// 운영의 forward-headers-strategy: native는 톰캣 RemoteIpValve를 켠다. 같은 로직의 RemoteIpFilter를
// 앞에 세워, 컨트롤러가 실제로 받는 요청 모양으로 검증한다.
class ClientIpTest {

    private static final String NGINX = "127.0.0.1";

    private String resolve(String remoteAddr, String xForwardedFor) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xForwardedFor != null) {
            request.addHeader("X-Forwarded-For", xForwardedFor);
        }
        RemoteIpFilter filter = new RemoteIpFilter();
        filter.init(new MockFilterConfig());
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return ClientIp.of((HttpServletRequest) chain.getRequest());
    }

    @Test
    @DisplayName("클라이언트가 X-Forwarded-For를 위조해 보내도 nginx가 붙인 실제 IP를 쓴다")
    void spoofedHeaderIgnored() throws Exception {
        // nginx는 $proxy_add_x_forwarded_for로 클라이언트가 보낸 값 뒤에 실제 IP를 덧붙인다.
        assertThat(resolve(NGINX, "6.6.6.6, 203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("위조가 없으면 nginx가 붙인 IP 그대로다")
    void plainForwarded() throws Exception {
        assertThat(resolve(NGINX, "203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("믿는 프록시를 거치지 않은 요청은 헤더를 무시하고 접속 IP를 쓴다")
    void directConnectionIgnoresHeader() throws Exception {
        assertThat(resolve("203.0.113.9", "6.6.6.6")).isEqualTo("203.0.113.9");
    }
}
