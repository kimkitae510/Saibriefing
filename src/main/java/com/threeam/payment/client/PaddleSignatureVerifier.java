package com.threeam.payment.client;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 패들 웹훅이 정말 패들에서 온 것인지 확인한다.
//
// 웹훅 주소는 인증 없이 열려 있어 누구나 POST를 쏠 수 있다. 패들은 본문과 시각을
// 우리만 아는 비밀키로 해시해 Paddle-Signature 헤더에 담아 보내므로, 같은 계산을
// 우리가 다시 해서 값이 맞는지 본다. 비밀키가 없으면 같은 해시를 만들 수 없다.
//
// 헤더 형식: ts=<epoch초>;h1=<HMAC-SHA256 hex>
// 서명 대상: "<ts>:<본문 원문>" — 본문을 한 글자라도 손대면 해시가 달라진다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PaddleSignatureVerifier {

    // 오래된 서명의 재전송(replay)을 막는 창. 패들 권장값은 더 짧지만, 서버 시계가 조금
    // 어긋났다고 진짜 웹훅을 버리면 지급이 늦어지므로 넉넉히 잡는다.
    private static final long TOLERANCE_SECONDS = 300;

    private final PaymentProperties properties;

    public boolean verify(String rawBody, String signatureHeader) {
        String secret = properties.getPaddle().getWebhookSecret();
        // 키가 없으면 검증할 방법이 없다 — 통과시키지 않고 막는다(열어두면 검증이 없는 것과 같다).
        if (secret == null || secret.isBlank()) {
            log.error("패들 웹훅 시크릿 미설정 — 검증할 수 없어 거부함");
            return false;
        }
        if (rawBody == null || signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }

        String timestamp = null;
        String received = null;
        for (String part : signatureHeader.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if ("ts".equals(key)) {
                timestamp = value;
            } else if ("h1".equals(key)) {
                received = value;
            }
        }
        if (timestamp == null || received == null) {
            log.warn("패들 웹훅 서명 형식 불량: {}", signatureHeader);
            return false;
        }
        if (isStale(timestamp)) {
            log.warn("패들 웹훅 서명 시각이 허용 범위를 벗어남 ts={}", timestamp);
            return false;
        }

        String expected = hmacHex(secret, timestamp + ":" + rawBody);
        if (expected == null) {
            return false;
        }
        // 앞자리부터 비교하다 다르면 즉시 끝내는 방식은 응답 시간으로 정답을 좁힐 여지를 준다.
        // 길이와 무관하게 같은 시간이 걸리는 비교를 쓴다.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                received.getBytes(StandardCharsets.UTF_8));
    }

    private boolean isStale(String timestamp) {
        try {
            long age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
            return age > TOLERANCE_SECONDS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            log.error("패들 웹훅 서명 계산 실패", e);
            return null;
        }
    }
}
