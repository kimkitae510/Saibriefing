package com.threeam.story.entity;

// 지난번에 어떻게 다시 만나게 됐는가. 재회 경험이 있을 때만 묻는다 — 상대가 먼저 돌아온 적이
// 있는 판과 유저가 매달려 붙인 판은 이번에 기다려야 할지 움직여야 할지가 정반대다.
public enum PriorReunionPath {
    PARTNER_RETURNED("상대가 먼저 돌아왔다"),
    I_HELD_ON("유저가 붙잡아서 다시 만났다"),
    DRIFTED_BACK("자연스럽게 다시 연락이 닿았다"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");
    private final String label;
    PriorReunionPath(String label) {
        this.label = label;
    }
    public String label() {
        return label;
    }
}
