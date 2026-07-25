package com.threeam.match;

// 확률 대역이 정하는 것은 "무엇을 몇 장까지 보여줄 수 있나"의 상한뿐이다. 실제 장수는 LLM이
// 후보의 닮은 정도를 보고 정한다 — 억지로 채운 사례 한 장이 나머지까지 못 믿게 만든다.
//
// 확률과 구성을 맞추는 이유: 상위 몇 건을 태그 우연에 맡기면 확률이 낮은 사람에게 성공 사례만,
// 높은 사람에게 실패 사례만 뜰 수 있다. 그건 중립이 아니라 무작위다.
//
// 실패는 어느 대역에서도 1장을 넘지 않는다. 유료로 산 화면이 절망만 파는 건 다른 종류의 거짓말이다.
public enum MatchBand {

    LOW(1, 1),
    MID(2, 1),
    // 높은 대역엔 실패를 안 싣는다 — 확률이 이미 말한 것을 카드로 한 번 더 뒤집지 않는다.
    HIGH(2, 0);

    // 경계는 프론트 bandLabel과 같은 값을 쓴다(45 미만 낮음, 65 이상 높음).
    // 화면이 "높음"이라고 말하는데 사례 구성은 보통인 상태가 생기면 유저가 둘 중 하나를 못 믿는다.
    private static final int MID_FLOOR = 45;
    private static final int HIGH_FLOOR = 65;

    private final int successMax;
    private final int failMax;

    MatchBand(int successMax, int failMax) {
        this.successMax = successMax;
        this.failMax = failMax;
    }

    public int successMax() {
        return successMax;
    }

    public int failMax() {
        return failMax;
    }

    public int totalMax() {
        return successMax + failMax;
    }

    // 확률이 없는 판정(사귀는 중, 재회 성공, 근거 부족)은 가장 보수적인 구성으로 본다 —
    // 확률이 말해주지 않으니 성공을 두 장 밀 근거도 없다.
    public static MatchBand of(Integer probability) {
        if (probability == null) {
            return LOW;
        }
        if (probability >= HIGH_FLOOR) {
            return HIGH;
        }
        return probability >= MID_FLOOR ? MID : LOW;
    }
}
