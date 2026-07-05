package com.threeam.story.entity;

// 이별 직전 관계가 어떻게 변했는가. 충동형과 소진형을 가르는 축이라 진단이 유형을 고를 때 쓴다.
// 매칭에는 못 쓴다 — 사례 데이터에 이 라벨이 없다.
// 라벨은 프롬프트 문장이 아니라 분류 어휘다(MatchTaxonomy와 같은 성격) — 화면과 프롬프트가
// 같은 말을 봐야 해서 한 곳에 둔다.
public enum PreBreakupChange {
    SUDDEN("전조 없이 갑자기"),
    FIGHTS_INCREASED("몇 주 전부터 싸움이 늘었다"),
    GRADUAL_COOLING("몇 달에 걸쳐 서서히 식었다"),
    REPEATED_WARNINGS("상대가 전부터 여러 번 힘들다고 말했다"),
    SINGLE_INCIDENT("잘 지내다 사건 하나로"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");

    private final String label;

    PreBreakupChange(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
