package com.threeam.story.entity;

// 시간을 갖기로 할 때 정한 기간. 시간을 갖는 중일 때만 묻는 가지다.
// 기간을 정하지 않은 것과 모르는 것을 가른다 — 기간 없는 시간 갖기는 상대가 결론을 미룬 것이고,
// 모름은 유저가 못 들은 것이다.
public enum PausePlan {
    NONE_SET("기간을 정하지 않았다"),
    WITHIN_TWO_WEEKS("2주 안"),
    ABOUT_A_MONTH("한 달 정도"),
    LONGER("한 달보다 길게"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");

    private final String label;

    PausePlan(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
