package com.threeam.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "입력값이 올바르지 않습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C002", "허용되지 않은 HTTP 메서드입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C003", "서버 오류가 발생했습니다."),

    // 회원
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "U001", "이미 사용 중인 이메일입니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "U002", "사용자를 찾을 수 없습니다."),
    SIGNUP_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "U003",
            "오늘 이 인터넷 회선에서 만든 계정이 많습니다. 이미 계정이 있다면 로그인해 주세요."),
    // 코드 불일치와 "코드 발급 이력 없음"을 한 코드로 합친다 — 응답이 갈리면 발급 여부 추측에 쓰인다.
    VERIFICATION_CODE_INVALID(HttpStatus.BAD_REQUEST, "U004", "인증 코드가 올바르지 않습니다. 다시 확인해 주세요."),
    VERIFICATION_CODE_EXPIRED(HttpStatus.BAD_REQUEST, "U005", "인증 코드가 만료되었습니다. 코드를 다시 요청해 주세요."),
    VERIFICATION_RESEND_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "U006", "인증 메일을 방금 보냈습니다. 1분 후에 다시 요청해 주세요."),
    VERIFICATION_ATTEMPTS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "U007", "인증 시도가 너무 많습니다. 코드를 다시 요청해 주세요."),
    MAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "U008", "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도해 주세요."),
    // 가입 필수 동의와 결제 청약철회 고지 동의가 공용으로 쓴다
    CONSENT_REQUIRED(HttpStatus.BAD_REQUEST, "U009", "필수 동의 항목이 누락되었습니다."),
    // 게스트 차단(분석, 결제)과 게스트 대화 소진이 공용으로 쓴다 — 프론트는 이 코드로 계정 연결을 유도한다
    GUEST_LINK_REQUIRED(HttpStatus.FORBIDDEN, "U010",
            "둘러보기로 이용할 수 있는 범위를 넘었습니다. 계정을 연결하면 지금까지의 대화를 그대로 이어갈 수 있습니다."),
    // 둘러보기도 계정 생성이라 가입과 같은 IP 상한에 걸린다. 가입 문구를 그대로 쓰면 가입한 적도
    // 없는 사람에게 "가입 요청이 많다"고 말하게 돼 뜻이 통하지 않는다.
    // "이어서 볼 수 있다"고도 쓰지 않는다 — 시작 자체가 막힌 자리라 이어질 대화가 없다.
    // 소셜 경로는 이 상한을 타지 않으므로 카카오/네이버 안내는 실제로 통하는 길이다.
    GUEST_START_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "U011",
            "오늘 이 인터넷 회선에서 로그인 없이 시작한 횟수가 많습니다. 카카오나 네이버로 시작하면 바로 이용할 수 있습니다."),

    // 인증
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "A001", "비밀번호가 올바르지 않습니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "A002", "유효하지 않은 토큰입니다."),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A003", "인증이 필요합니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "A004", "접근 권한이 없습니다."),
    // 이메일 존재 여부가 응답으로 갈리면 계정 수집(enumeration)에 쓰인다. 로그인 실패는 이 하나로 통일.
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "A005", "이메일 또는 비밀번호가 올바르지 않습니다."),
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "A006", "로그인 시도가 너무 많습니다. 15분 후에 다시 시도해 주세요."),
    OAUTH_FAILED(HttpStatus.BAD_GATEWAY, "A007", "소셜 로그인에 실패했습니다. 잠시 후 다시 시도해 주세요."),
    // 사용자 확정 정책: 소셜 이메일이 기존 계정과 겹치면 통합하지 않고 거부 안내(계정 탈취 여지 차단)
    OAUTH_EMAIL_CONFLICT(HttpStatus.CONFLICT, "A008", "이미 가입된 이메일입니다. 기존 방법으로 로그인해 주세요."),
    OAUTH_WITHDRAWN_ACCOUNT(HttpStatus.FORBIDDEN, "A009", "탈퇴한 계정입니다. 같은 소셜 계정으로는 다시 가입할 수 없습니다."),
    SOCIAL_ACCOUNT_NO_PASSWORD(HttpStatus.BAD_REQUEST, "A010", "소셜 로그인으로 가입한 계정은 비밀번호가 없습니다."),

    // 사연
    STORY_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "사연을 찾을 수 없습니다."),
    // 재시도 버튼을 두 번 눌렀거나, 그새 답이 붙은 경우. 화면을 새로 그리면 해소된다.
    CHAT_RETRY_NOT_APPLICABLE(HttpStatus.CONFLICT, "S002", "다시 시도할 답변이 없습니다."),
    // 유저가 직접 적은 사실의 취소/수정 대상이 없거나(이미 지움), 추출된 사실을 지우려 한 경우.
    STORY_FACT_NOT_FOUND(HttpStatus.NOT_FOUND, "S003", "남긴 기록을 찾을 수 없습니다."),

    // 분석
    ASSESSMENT_NO_MESSAGES(HttpStatus.BAD_REQUEST, "AS001", "분석할 대화 내용이 없습니다."),
    // AS002(새 대화 없음 거부)는 폐지 — 같은 재료 재분석도 유저의 선택이고 후차감이라 비용은
    // 본인이 진다. 코드 번호는 결번으로 남긴다.
    // AS003(새 사실 없음 거부)은 폐지 — temperature 0으로 출렁임이 해소됐고, 추출 누락 시
    // 분석의 자가 복구를 막는 부작용이 있었다. 코드 번호는 결번으로 남긴다.
    // DATING 상태 폐지로 이름을 잠금 일반형으로 — 지금 이 창구가 받는 잠금은 재회 성공뿐이다.
    ASSESSMENT_NOT_LOCKED(HttpStatus.CONFLICT, "AS004", "지금은 재회 성공으로 분석된 상태가 아닙니다."),
    ASSESSMENT_NOT_OFFER(HttpStatus.CONFLICT, "AS005", "지금은 상대의 재회 제안으로 확정된 상태가 아닙니다."),
    ASSESSMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "AS006", "분석을 찾을 수 없습니다."),

    // 분석 공유
    SHARE_NOT_FOUND(HttpStatus.NOT_FOUND, "SH001", "공유된 분석을 찾을 수 없습니다. 링크가 잘못되었거나 삭제된 이야기입니다."),
    // 분석 기록이 없거나 잠금 판정(사귀는 중, 재회 성공)이라 공유할 확률 화면이 없는 경우
    SHARE_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "SH002", "공유할 분석 결과가 없습니다."),

    // 사례 매칭
    // 유료 매칭은 진단 결과에 묶여 저장되므로 진단이 한 번은 있어야 돌릴 수 있다.
    MATCH_NOT_AVAILABLE(HttpStatus.BAD_REQUEST, "M001", "먼저 분석을 받아야 비슷한 사례를 찾을 수 있습니다."),

    // 분석 평가
    REVIEW_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND, "R001", "평가할 분석이 없습니다."),
    // R002(중복 평가)는 결번 — 점수가 업서트로 바뀌며 중복이라는 개념 자체가 사라졌다.

    // LLM
    LLM_GENERATION_FAILED(HttpStatus.BAD_GATEWAY, "L001", "AI 응답 생성에 실패했습니다."),
    // 비동기 HTTP 대기 초과. 작업 자체는 뒤에서 끝나 저장됐을 수 있어 새로고침을 안내한다.
    ASYNC_REQUEST_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "L002",
            "응답이 오래 걸려 연결이 끊겼습니다. 결과가 저장되었을 수 있으니 화면을 새로고침해 확인해 주세요."),

    // 사용량 제한
    QUOTA_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Q001",
            "남은 횟수를 모두 사용했습니다. 이용권을 충전하면 이어서 이용할 수 있습니다."),
    GENERATION_IN_PROGRESS(HttpStatus.TOO_MANY_REQUESTS, "Q002", "아직 이전 답변을 만드는 중입니다. 잠시만 기다려 주세요."),
    // 남은 시간은 문구에 박지 않는다 — retryAfterSeconds로 내려가 화면이 카운트다운으로 보여준다.
    // 문구에 "1분 뒤"처럼 적어두면 쿨다운을 조정할 때마다 여기까지 같이 고쳐야 한다.
    CHAT_RETRY_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "Q003",
            "답변을 만들지 못하는 상태가 이어지고 있습니다. 이번 대화는 차감되지 않았습니다."),

    // 결제
    PAYMENT_ITEM_NOT_FOUND(HttpStatus.BAD_REQUEST, "P001", "존재하지 않는 상품입니다."),
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "P002", "결제 내역을 찾을 수 없습니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "P003", "결제 금액이 주문 금액과 다릅니다."),
    PAYMENT_INVALID_STATE(HttpStatus.CONFLICT, "P004", "지금 상태에서는 처리할 수 없는 결제입니다."),
    PAYMENT_ALREADY_PROCESSING(HttpStatus.TOO_MANY_REQUESTS, "P005", "결제를 처리하는 중입니다. 잠시만 기다려 주세요."),
    PAYMENT_CONFIRM_REJECTED(HttpStatus.BAD_REQUEST, "P006", "결제가 승인되지 않았습니다. 다른 수단으로 다시 시도해 주세요."),
    PAYMENT_RESULT_PENDING(HttpStatus.BAD_GATEWAY, "P007",
            "결제 결과 확인이 지연되고 있습니다. 잠시 후 결제 내역에서 확인해 주세요. 완료된 결제는 자동으로 반영됩니다."),
    PAYMENT_CANCEL_REJECTED(HttpStatus.BAD_GATEWAY, "P008", "환불 처리가 거절되었습니다. 잠시 후 다시 시도해 주세요."),
    REFUND_NOT_ALLOWED(HttpStatus.CONFLICT, "P009", "이미 사용을 시작한 이용권은 환불할 수 없습니다."),
    REFUND_ACCOUNT_REQUIRED(HttpStatus.BAD_REQUEST, "P010", "가상계좌 결제는 환불받을 계좌 정보가 필요합니다."),
    TOO_MANY_PENDING_ORDERS(HttpStatus.TOO_MANY_REQUESTS, "P011",
            "결제되지 않은 주문이 너무 많습니다. 진행 중인 결제를 마치거나 잠시 후 다시 시도해 주세요."),
    // PG 심사 전이라 실결제를 열 수 없는 기간에 쓴다. mock은 무단 승인이라 운영에 못 올리고,
    // toss는 키가 없어 위젯이 깨진다 — 결제만 명시적으로 닫아두는 상태가 따로 필요했다.
    PAYMENT_DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "P012",
            "결제 준비 중입니다. 조금만 기다려 주세요."),
    // 상품은 있는데 그에 대응하는 PG 쪽 가격 설정이 비어 있는 경우(설정 누락). 유저 잘못이
    // 아니므로 5xx로 알리고, 서버 로그에서 어떤 상품이 빠졌는지 찾을 수 있게 한다.
    PAYMENT_PRICE_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "P013",
            "결제 준비 중입니다. 조금만 기다려 주세요.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
