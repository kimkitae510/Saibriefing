package com.threeam.payment.entity;

import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.usage.UsageKind;
import java.util.List;
import lombok.Getter;

// 판매 상품 정의. 금액은 항상 서버의 이 정의가 기준이다 — 프론트가 보내는 금액은
// 검증 대상일 뿐 절대 가격 결정에 쓰지 않는다(위변조 차단).
// 가격을 바꿔도 지난 결제는 payments.amount에 당시 금액이 박제되어 있어 영향 없다.
//
// 한 상품이 여러 종류의 이용권을 지급할 수 있다(묶음).
// 환불은 전량 미사용일 때만 전액 — 부분 환불(회당 가치 가중)은 폐지되어 회당 가치 정의가 없다.
@Getter
public enum PaymentItem {

    // 단일 상품으로 시작한다 — 등급을 여럿 두면 유저는 고민하고 우리는 무엇이 팔릴지 모른다.
    // 소진 속도와 재구매율을 보고 늘린다. 구성 근거(2026-05 실비):
    // 분석 1회 82~122원(사연 복잡도에 따라 추론량이 3배까지 뛴다).
    //
    // 이름을 "리포트 1건"으로 둔다 — 결제와 동시에 결과물이 나가는 구조가 이름에서 드러나야
    // 한다. 국내 PG가 위험으로 꼽는 건 이용권, 포인트 충전처럼 결제와 이행 사이에 시차가
    // 생기는 형태다. 미이행 채무가 안 쌓이는 게 이 상품의 성질이고 그게 심사에서 유리하다.
    //
    // 채팅 회차는 팔지 않는다. 산 것을 그 자리에서 열어보게 하는 게 결제 경험에도 낫고,
    // 결제사 심사에서도 안전하다 — 선불 회차가 계정에 쌓이면 미이행 채무로 잡힌다.
    // 대화는 무료 지급분(게스트 체험, 가입 선물)으로 돌리고, 파는 것은 결과물뿐이다.
    // 이름에 "매칭"을 쓰지 않는다 — 번역하면 matching이 되고, 연애 서비스에서 그 단어는
    // 결제 대행사가 금지하는 데이팅, 중개로 읽힌다(실측). 하는 일은 비슷한 사례를 찾아 보여주는 것이다.
    // 1회만 준다 — 분석 1회에 묶이는 기능이라 그 이상은 쓸 데가 없다
    // (같은 분석을 다시 눌러도 저장분이 나가고 쿼터를 안 쓴다).
    BUNDLE_STANDARD("분석 리포트 1건", 6900, List.of(
            new Grant(UsageKind.ASSESSMENT, 1),
            new Grant(UsageKind.MATCH, 1)));

    private final String displayName;
    private final int amount;
    private final List<Grant> grants;

    PaymentItem(String displayName, int amount, List<Grant> grants) {
        this.displayName = displayName;
        this.amount = amount;
        this.grants = grants;
    }

    public record Grant(UsageKind kind, int count) {
    }

    public static PaymentItem parse(String code) {
        try {
            return valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ErrorCode.PAYMENT_ITEM_NOT_FOUND);
        }
    }
}
