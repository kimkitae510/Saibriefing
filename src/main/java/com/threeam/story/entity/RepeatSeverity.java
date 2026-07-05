package com.threeam.story.entity;

// 재이별일 때 이번 이별이 지난번과 비교해 어떤 무게였는가. 재회 경험이 있을 때만 묻는다 —
// 같은 이유라도 지난번엔 며칠 냉전이고 이번엔 차단이면 온오프 사이클이 아니라 끝을 낸 것이다.
public enum RepeatSeverity {
    MORE_FINAL("지난번보다 훨씬 단호했다"),
    SIMILAR("지난번과 비슷했다"),
    LIGHTER("지난번보다 가벼웠다"),
    UNSURE("잘 모르겠다"),
    OTHER("기타");
    private final String label;
    RepeatSeverity(String label) {
        this.label = label;
    }
    public String label() {
        return label;
    }
}
