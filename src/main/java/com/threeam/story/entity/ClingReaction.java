package com.threeam.story.entity;

// 헤어진 뒤 유저가 붙잡았는지와 그때 상대의 반응. 지금 연락 상태보다 먼저 굳어진 값이라
// 사연을 안 읽고도 물을 수 있고, 매달렸을 때 흔들렸는지 단호했는지가 상대 마음의 온도를
// 가장 직접 보여준다. 매칭 사전에 없는 축이라 프롬프트 재료로만 간다.
public enum ClingReaction {
    CLUNG_FIRM("매달렸는데 상대는 단호했다"),
    CLUNG_WAVERED("매달리니 상대가 흔들렸다"),
    LITTLE("조금 하다 말았다"),
    NONE("잡지 않았다"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");
    private final String label;
    ClingReaction(String label) {
        this.label = label;
    }
    public String label() {
        return label;
    }
}
