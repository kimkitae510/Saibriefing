package com.threeam.story.entity;

// 이 상대와 헤어진 적이 있을 때, 그때와 이번이 같은 문제였는가. 재회 경험이 있을 때만 묻는다 —
// 같은 문제가 되풀이된 이별과 매번 다른 이유로 끝난 이별은 다시 만나도 갈 길이 다르다.
public enum RepeatBreakupPattern {
    SAME_ISSUE("같은 문제로"),
    DIFFERENT("매번 다른 이유로"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");
    private final String label;
    RepeatBreakupPattern(String label) {
        this.label = label;
    }
    public String label() {
        return label;
    }
}
