package com.threeam.global.web;

import jakarta.servlet.http.HttpServletRequest;

// X-Forwarded-For 헤더를 직접 읽지 않는다. 첫 항목은 클라이언트가 보낸 값이라 위조된다.
// 운영은 forward-headers-strategy: native(톰캣 RemoteIpValve)가 헤더를 오른쪽부터 읽어 nginx가 붙인
// 실제 IP를 remoteAddr에 넣고, 헤더에는 처리 못 한 앞부분(위조분)만 남긴다. 그래서 remoteAddr만 믿는다.
public final class ClientIp {

    private ClientIp() {
    }

    public static String of(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
