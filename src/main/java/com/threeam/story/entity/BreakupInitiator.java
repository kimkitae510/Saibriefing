package com.threeam.story.entity;

// "누가 먼저 이별을 원했나요"의 답. 사전(MatchTaxonomy.DUMPERS)의 네 값에 그대로 대응한다.
// PUSHED(나떠밀림)를 선택지로 세운 것이 이 폼의 핵심이다 — 말은 유저가 꺼냈지만 상대가
// 밀어붙여 만든 이별을 지금은 LLM이 산문에서 추론해야 하고, 놓치면 자기 마음이 식어서 찬
// 사례가 최상위로 붙는다(실측). 유저에게 직접 물으면 그 추론이 사라진다.
// BOTH를 두지 않는다 — 사전에 "양쪽"이 없어 새로 만들면 사례 쪽에 없는 값이라 점수를
// 통째로 잃는다. 화면에서는 "잘 모르겠다"가 그 자리를 받는다.
public enum BreakupInitiator {
    PARTNER("상대", "상대가 먼저 원했다"),
    SELF("나", "유저가 먼저 원했다"),
    PUSHED("나떠밀림", "말은 유저가 꺼냈지만 상대가 그렇게 만들었다"),
    UNKNOWN("미상", "유저도 모르겠다고 함"),
    OTHER(null, "기타");

    private final String dumper;
    private final String label;

    BreakupInitiator(String dumper, String label) {
        this.dumper = dumper;
        this.label = label;
    }

    public String dumper() {
        return dumper;
    }

    public String label() {
        return label;
    }
}
