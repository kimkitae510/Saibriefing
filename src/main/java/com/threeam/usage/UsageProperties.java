package com.threeam.usage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 사용량 설정. 잔여 회수는 전부 이용권(entitlements) 하나로 관리한다 — 일일 무료 쿼터는
// 폐지됐다(유저당 월 원가가 1만원을 넘어 지속 불가, 2026-05 실비 실측). 무료로 주는 것도
// 이용권으로 지급하므로, 나중에 이벤트나 재방문 선물을 붙일 때도 지급 한 줄이면 된다.
@Getter
@Setter
@ConfigurationProperties(prefix = "usage")
public class UsageProperties {

    // 직행 가입 선물. 게스트 체험(3회)과 총량을 맞춰 어느 문으로 들어와도 대화 3 + 분석 1.
    // 대화 0으로 두면 게스트를 안 거친 가입자는 사연을 말할 길이 없다.
    private int signupGiftChat = 3;
    private int signupGiftAssessment = 1;

    // 게스트 체험. 분석은 0회 — 계정 연결을 유도하는 지점이라 아예 주지 않는다.
    private int guestTrialChat = 3;

    // 게스트가 계정을 연결할 때 주는 대화. 체험으로 이미 받았으므로 없다 — 연결이 여는 것은 분석이다.
    private int guestUpgradeGiftChat = 0;

    // 분석 평가 보상. 유저당 1회 — 첫 평가에만 나간다(평가 자체는 분석마다 남길 수 있다).
    private int reviewGiftChat = 1;

    // 생성 락의 자동 만료(TTL). LLM 호출이 실패로 락을 못 풀어도 이 시간이 지나면 풀린 것으로 본다.
    // 반드시 해당 종류의 LLM 타임아웃보다 커야 한다 — 짧으면 아직 진행 중인 생성 위로 두 번째
    // 요청이 만료된 락을 뺏어(acquire의 IF locked_until < now) 동시 생성이 된다.
    // 채팅은 목표 판정(50초)과 답변(50초)이 순차라 합 100초 < 110초. 정상 종료든 실패든 콜백에서
    // 즉시 반납하므로 이 값이 실제로 쓰이는 건 프로세스 강제 종료나 콜백 유실뿐이다.
    private long chatLockTtlSeconds = 110;
    private long assessmentLockTtlSeconds = 100;
    // 유료 매칭. 후보 열넷의 본문을 읽는 호출이라 분석과 같은 무게로 잡는다.
    private long matchLockTtlSeconds = 100;

    // 채팅 연속 실패 가드. 분석(2회/3분)보다 느슨하다 — 채팅은 턴이 짧고 잦아 2회면 일시 장애에
    // 걸리고, 대화 리듬이라 3분은 이탈로 이어진다. 1분이면 분당 1회로 캡돼 비용 방어엔 충분하다.
    private int chatFailStreakLimit = 3;
    private long chatFailCooldownSeconds = 60;

    // 종류가 셋이 되면서 삼항으로는 못 가른다 — 새 종류를 추가할 때 switch가 빠진 가지를
    // 컴파일 단계에서 알려주도록 enhanced switch로 둔다(삼항이면 조용히 분석 값을 쓴다).
    public long lockTtlSeconds(UsageKind kind) {
        return switch (kind) {
            case CHAT -> chatLockTtlSeconds;
            case ASSESSMENT -> assessmentLockTtlSeconds;
            case MATCH -> matchLockTtlSeconds;
        };
    }

    // 매칭은 가입 선물이 없다 — 유료 분석을 풀 때 함께 지급한다.
    public int signupGift(UsageKind kind) {
        return switch (kind) {
            case CHAT -> signupGiftChat;
            case ASSESSMENT -> signupGiftAssessment;
            case MATCH -> 0;
        };
    }
}
