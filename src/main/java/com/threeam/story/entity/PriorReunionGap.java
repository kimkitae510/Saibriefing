package com.threeam.story.entity;

// 지난번엔 헤어지고 얼마 만에 다시 만났는가. 재회 경험이 있을 때만 묻는다.
// 구간으로 받는다 — 지난 일은 기억이 "두 달쯤"이 한계라 개월과 일을 요구하면 헛된 정밀도다.
// 지금 경과와 나란히 놓으면 아직 사이클 안인지 이미 넘었는지가 갈린다. 상한(일)은 그 비교용.
public enum PriorReunionGap {
    WITHIN_WEEK("일주일 안", 7),
    WITHIN_MONTH("한 달 안", 30),
    WITHIN_QUARTER("석 달 안", 90),
    WITHIN_HALF_YEAR("반년 안", 180),
    LONGER("반년보다 오래", null),
    UNSURE("잘 모르겠다", null),
    OTHER("기타", null);
    private final String label;
    private final Integer maxDays;
    PriorReunionGap(String label, Integer maxDays) {
        this.label = label;
        this.maxDays = maxDays;
    }
    public String label() {
        return label;
    }
    // 구간의 상한. 상한이 없는 값(더 오래, 모름, 기타)은 null.
    public Integer maxDays() {
        return maxDays;
    }
}
