package com.threeam.story.entity;

// 이별 후 상대가 먼저 한 행동. 현재 연락 상태보다 강한 신호다 — 지금 무연락이어도 한 번
// 먼저 왔던 판과 한 번도 안 온 판은 다르다. 여러 개 고를 수 있다.
// NOTHING과 UNKNOWN은 다른 값과 함께 오면 그것만 남긴다(화면에서도 배타 선택).
public enum PartnerAction {
    REACHED_OUT("먼저 연락해 왔다"),
    ASKED_TO_MEET("만나자고 했다"),
    SNS_TRACE("SNS 흔적을 남긴다"),
    BELONGINGS_ONLY("물건이나 정리 얘기만 했다"),
    CUT_OFF("차단하거나 정리했다"),
    NOTHING("아무것도 없었다"),
    UNKNOWN("잘 모르겠다"),
    OTHER("기타");

    private final String label;

    PartnerAction(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
