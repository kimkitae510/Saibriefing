package com.threeam.global.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NetworkFailureReasonTest {

    @Test
    @DisplayName("연결 타임아웃은 요청 타임아웃의 하위지만 따로 센다")
    void connectTimeout_beforeTimeout() {
        assertThat(NetworkFailureReason.of(new CompletionException(new HttpConnectTimeoutException("connect"))))
                .isEqualTo("connect_timeout");
        assertThat(NetworkFailureReason.of(new CompletionException(new HttpTimeoutException("request"))))
                .isEqualTo("timeout");
    }

    @Test
    @DisplayName("JDK가 이름 풀이 실패를 ConnectException으로 감싸도 dns로 본다")
    void dns_evenWhenWrappedInConnectException() {
        ConnectException wrapped = new ConnectException();
        wrapped.initCause(new UnresolvedAddressException());
        assertThat(NetworkFailureReason.of(new CompletionException(wrapped))).isEqualTo("dns");
        assertThat(NetworkFailureReason.of(new UnknownHostException("api.openai.com"))).isEqualTo("dns");
    }

    @Test
    @DisplayName("연결 거절은 connect_failed, 연결 뒤 끊김은 network")
    void connectFailed_vsNetwork() {
        assertThat(NetworkFailureReason.of(new CompletionException(new ConnectException("refused"))))
                .isEqualTo("connect_failed");
        assertThat(NetworkFailureReason.of(new CompletionException(new IOException("Connection reset"))))
                .isEqualTo("network");
        // 스트림을 읽다 끊기면 UncheckedIOException으로 감싸여 온다.
        assertThat(NetworkFailureReason.of(new UncheckedIOException(new IOException("closed"))))
                .isEqualTo("network");
    }

    @Test
    @DisplayName("네트워크 실패가 아니면 null")
    void null_whenNotNetwork() {
        assertThat(NetworkFailureReason.of(null)).isNull();
        assertThat(NetworkFailureReason.of(new IllegalStateException())).isNull();
    }
}
