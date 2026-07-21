package com.threeam.global.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;

// 외부 호출이 응답 없이 끝난 이유를 지표 라벨로 가른다. 장애 때 볼 곳이 달라서다:
// dns, connect_failed, connect_timeout은 연결 전 단계(우리 망이나 상대 입구),
// network는 연결 뒤 끊김(중간 망), timeout은 연결은 됐는데 답이 늦음(상대 처리).
// 응답이 온 실패(상태코드)는 호출부가 가른다. 네트워크 실패가 아니면 null.
public final class NetworkFailureReason {

    // 원인 사슬이 자기 자신을 가리키는 예외에서 무한 반복을 막는다.
    private static final int MAX_DEPTH = 10;

    private NetworkFailureReason() {
    }

    public static String of(Throwable ex) {
        // JDK HttpClient는 이름 풀이 실패를 ConnectException으로 감싸 던진다. 바깥부터 보면 connect_failed로
        // 새므로 사슬 전체에서 먼저 찾는다.
        if (causedBy(ex, UnknownHostException.class) || causedBy(ex, UnresolvedAddressException.class)) {
            return "dns";
        }
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++, cause = cause.getCause()) {
            // 연결 타임아웃은 요청 타임아웃의 하위, 둘 다 IOException의 하위라 이 순서로 본다.
            if (cause instanceof HttpConnectTimeoutException) {
                return "connect_timeout";
            }
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException) {
                return "timeout";
            }
            if (cause instanceof ConnectException) {
                return "connect_failed";
            }
            if (cause instanceof IOException) {
                return "network";
            }
        }
        return null;
    }

    private static boolean causedBy(Throwable ex, Class<? extends Throwable> type) {
        Throwable cause = ex;
        for (int depth = 0; cause != null && depth < MAX_DEPTH; depth++, cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
