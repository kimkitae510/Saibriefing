package com.threeam.payment.client;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "payment")
public class PaymentProperties {

    private String provider = "mock";

    private Toss toss = new Toss();

    private Paddle paddle = new Paddle();

    // READY로 방치된 주문을 만료 처리하기까지의 시간. 위젯을 열어두고 고민하는 시간을 넉넉히 잡는다.
    private int orderExpireMinutes = 30;

    // 승인/취소 응답 불명(IN_PROGRESS, CANCEL_REQUESTED) 상태를 재동기화하기까지의 대기.
    // 너무 짧으면 정상 진행 중인 승인과 경합하고, 너무 길면 유저가 "돈 나갔는데 지급이 없다"를 오래 본다.
    private int syncAfterMinutes = 2;

    // 유저당 미결(READY) 주문 상한. 결제까지 안 가는 주문 생성 도배로 테이블이 부푸는 것을 막는다.
    // 정상 유저는 위젯을 띄우면 바로 결제하거나 30분 뒤 만료되므로 이 수에 닿을 일이 없다.
    private int maxPendingOrdersPerUser = 5;

    @Getter
    @Setter
    public static class Toss {
        // client-key는 프론트 결제위젯용(공개 가능), secret-key는 서버 승인/취소용(절대 비공개).
        private String clientKey = "";
        private String secretKey = "";
        private String baseUrl = "https://api.tosspayments.com/v1";
        // 승인 응답 대기. 토스 권장값 60초 — 우리가 먼저 끊어도 토스는 승인을 끝까지 처리해서
        // 짧게 끊으면 결과 모름만 늘고, 재동기화가 조회로 확정할 때까지 유저가 기다린다.
        private long timeoutSeconds = 60;
    }

    @Getter
    @Setter
    public static class Paddle {
        // client-token은 프론트 결제창용(공개 가능), api-key는 서버 조회/환불용(절대 비공개).
        private String clientToken = "";
        private String apiKey = "";
        // 웹훅 서명 검증 키. 패들 대시보드에서 알림 대상을 만들 때 발급된다.
        private String webhookSecret = "";
        // 샌드박스는 sandbox-api.paddle.com, 운영은 api.paddle.com.
        private String baseUrl = "https://sandbox-api.paddle.com";
        // 운영 프로파일에서 샌드박스를 쓰는 것을 명시적으로 허용한다. 심사 전 검증 기간에만
        // 켠다 — 켜져 있으면 실결제가 되지 않으므로 오픈 전 점검 대상이다.
        private boolean allowSandboxInProd = false;
        private long timeoutSeconds = 30;
        // 상품 코드(PaymentItem) → 패들 가격 ID. 샌드박스와 운영의 ID가 다르고 상품 구성도
        // 바뀌므로 코드에 박지 않고 설정으로 주입한다.
        private Map<String, String> priceIds = new HashMap<>();
    }
}
