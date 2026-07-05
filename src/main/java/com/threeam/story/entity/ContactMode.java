package com.threeam.story.entity;

// 지금 연락 상태. 사전(MatchTaxonomy.CONTACT_STATES)보다 한 칸 잘게 받는다 —
// 사전의 "연락중"은 상대가 먼저 오는 판과 내가 보내야만 굴러가는 판을 한 값에 담는데,
// 이 둘은 재회 판정에서 정반대의 신호다. 매칭은 사전 값을 쓰고(사례에 없는 값을 만들면
// 12점을 통째로 잃는다), 진단 프롬프트는 여기 원본을 본다.
public enum ContactMode {
    PARTNER_REACHES("상대가연락", "상대가 먼저 연락해 온다"),
    MUTUAL("연락중", "서로 주고받는다"),
    // 답은 오지만 시작은 늘 나. 사전에 자리가 없어 "연락중"으로 접는다.
    I_INITIATE("연락중", "유저가 보내면 답은 온다"),
    READ_NO_REPLY("읽씹", "유저가 보내는데 읽고 답이 없다"),
    NONE("무연락", "연락이 아예 없다"),
    BLOCKED("차단", "차단당했다"),
    UNKNOWN(null, "잘 모르겠다"),
    OTHER(null, "기타");

    private final String contactState;
    private final String label;

    ContactMode(String contactState, String label) {
        this.contactState = contactState;
        this.label = label;
    }

    public String contactState() {
        return contactState;
    }

    public String label() {
        return label;
    }
}
