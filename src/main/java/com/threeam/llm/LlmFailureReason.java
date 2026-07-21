package com.threeam.llm;

import com.threeam.global.http.NetworkFailureReason;

// llm.calls의 reason 라벨. 세 프로바이더가 같은 분류를 쓴다 — 알림이 사유별로 갈리기 때문이다
// (크레딧 소진은 1건이면 critical, 타임아웃은 급증 warning). 사유를 못 가르면 그 알림은 영원히 안 울린다.
// 응답이 온 실패는 상태코드로, 응답이 안 온 실패는 네트워크 단계로 가른다.
// parse는 2xx가 왔는데 쓸 수 없는 응답(파싱 실패, 거절, 길이 잘림, 빈 응답)이다.
final class LlmFailureReason {

    private LlmFailureReason() {
    }

    static String of(Throwable ex) {
        if (ex == null) {
            return "none";
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof LlmCreditException) {
                return "credit";
            }
            if (cause instanceof LlmHttpStatusException statusEx) {
                return byStatus(statusEx.status);
            }
        }
        String network = NetworkFailureReason.of(ex);
        if (network != null) {
            return network;
        }
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof LlmException) {
                return "parse";
            }
        }
        return "other";
    }

    // 529는 Anthropic의 과부하 응답이다(구글, OpenAI의 503과 같은 성격).
    private static String byStatus(int status) {
        return switch (status) {
            case 429 -> "quota";
            case 503, 529 -> "overloaded";
            default -> "http_" + (status / 100) + "xx";
        };
    }
}
