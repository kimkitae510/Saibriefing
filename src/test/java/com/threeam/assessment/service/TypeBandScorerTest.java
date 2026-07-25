package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReplacementStage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TypeBandScorerTest {

    private final TypeBandScorer scorer = new TypeBandScorer();

    private AssessmentFactor factor(FactorName name, FactorLevel level) {
        return AssessmentFactor.of(name, level, "근거", null, null);
    }

    private AssessmentFactor replacement(FactorLevel level, ReplacementStage stage) {
        return AssessmentFactor.of(FactorName.REPLACEMENT, level, "근거", null, stage);
    }

    @Test
    @DisplayName("요인이 전부 중립이면 유형 대역의 중앙값이 나온다")
    void apply_neutralGivesBandCenter() {
        int score = scorer.apply(BreakupType.IMPULSIVE, JumpRule.NONE, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.NEUTRAL)));
        assertThat(score).isEqualTo(60); // 충동형 52~68의 중앙
    }

    @Test
    @DisplayName("5단 판정 — 매우X는 전체 폭, X는 절반 폭으로 움직인다")
    void apply_levelsScaleByStrength() {
        int weak = scorer.apply(BreakupType.SITUATIONAL, JumpRule.NONE, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE)));    // +5(절반)
        int strongDown = scorer.apply(BreakupType.SITUATIONAL, JumpRule.NONE, List.of(
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_UNFAVORABLE))); // -8(전체)
        assertThat(weak).isEqualTo(59);       // 54 + 5
        assertThat(strongDown).isEqualTo(46); // 54 - 8
    }

    @Test
    @DisplayName("관계자산은 ±6, 접점은 ±4로 움직인다")
    void apply_smallFactors() {
        int score = scorer.apply(BreakupType.BURNOUT, JumpRule.NONE, List.of(
                factor(FactorName.RELATIONSHIP_ASSET, FactorLevel.STRONG_FAVORABLE), // +6
                factor(FactorName.CONTACT_PATH, FactorLevel.FAVORABLE)));            // +2
        assertThat(score).isEqualTo(34); // 26 + 6 + 2
    }

    // 요인 폭의 합(43)은 대역 폭(12~16)보다 훨씬 크다. 대역에 그대로 가두면 요인 다섯쯤은
    // 계산에 실리지도 못하고 잘려서, 대역 밖 ±10까지만 허용한다.
    @Test
    @DisplayName("요인이 쌓이면 대역을 최대 10까지 벗어나고 거기서 멈춘다")
    void apply_clampsToBandWithHeadroom() {
        int score = scorer.apply(BreakupType.TRUST_BROKEN, JumpRule.NONE, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE),        // +5
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_FAVORABLE),   // +8
                factor(FactorName.NOTICE_TONE, FactorLevel.STRONG_FAVORABLE),    // +6
                factor(FactorName.PARTNER_PATTERN, FactorLevel.STRONG_FAVORABLE))); // +5
        assertThat(score).isEqualTo(28); // 12 + 24 = 36 → 상한 18+10
    }

    @Test
    @DisplayName("새 연인 정착만 대역 하한을 무시하고 내려간다")
    void apply_settledReplacementIgnoresFloor() {
        int seeing = scorer.apply(BreakupType.FADED, JumpRule.NONE, List.of(
                replacement(FactorLevel.UNFAVORABLE, ReplacementStage.SEEING),   // -6
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_UNFAVORABLE))); // -10
        int settled = scorer.apply(BreakupType.FADED, JumpRule.NONE, List.of(
                replacement(FactorLevel.UNFAVORABLE, ReplacementStage.SETTLED),  // -12
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_UNFAVORABLE)));
        assertThat(seeing).isEqualTo(10); // 26 - 16 = 10, 하한 20-10과 정확히 만남
        assertThat(settled).isEqualTo(4); // 26 - 22, 하한을 안 봄
    }

    // 재회는 '왜 헤어졌나'보다 '이별 후에 무엇이 있었나'로 더 잘 예측된다. 다만 점프가 사유를
    // 지우면 외도로 헤어진 판에 안부 연락 한 번이 와도 접촉재개 대역만 남는다.
    @Test
    @DisplayName("점프는 사유 대역을 끌어당기되 지우지 않는다 — 같은 점프라도 사유가 다르면 값이 다르다")
    void jumpPullsBandInsteadOfReplacingIt() {
        int fromTrust = scorer.apply(BreakupType.TRUST_BROKEN, JumpRule.PARTNER_RECONTACT, List.of());
        int fromBurnout = scorer.apply(BreakupType.BURNOUT, JumpRule.PARTNER_RECONTACT, List.of());

        assertThat(fromTrust).isEqualTo(26);   // 6~18을 30~45 쪽으로 0.55만큼
        assertThat(fromBurnout).isEqualTo(33); // 20~33에서 출발하니 더 높다
    }

    // 연락이 왔다는 사실이 확률을 낮추는 근거가 될 수는 없다.
    @Test
    @DisplayName("좋은 소식 점프는 사유 대역이 이미 더 높으면 끌어내리지 않는다")
    void upwardJumpNeverLowers() {
        int plain = scorer.apply(BreakupType.IMPULSIVE, JumpRule.NONE, List.of());
        int recontact = scorer.apply(BreakupType.IMPULSIVE, JumpRule.PARTNER_RECONTACT, List.of());

        assertThat(recontact).isEqualTo(plain); // 충동형(52~68)이 접촉재개(30~45)보다 높다
    }

    @Test
    @DisplayName("나쁜 소식 점프는 사유 대역이 이미 더 낮으면 끌어올리지 않는다")
    void downwardJumpNeverRaises() {
        int plain = scorer.apply(BreakupType.TRUST_BROKEN, JumpRule.NONE, List.of());
        int closed = scorer.apply(BreakupType.TRUST_BROKEN, JumpRule.PARTNER_CLOSED, List.of());

        assertThat(closed).isEqualTo(plain); // 신뢰붕괴(6~18)가 문닫힘(8~18)보다 낮다
    }

    @Test
    @DisplayName("하향 점프 — 유리한 유형이어도 문닫힘과 결혼약혼이 바닥 근처로 끌어내린다")
    void apply_downwardJumps() {
        int closed = scorer.apply(BreakupType.IMPULSIVE, JumpRule.PARTNER_CLOSED, List.of());
        int married = scorer.apply(BreakupType.IMPULSIVE, JumpRule.PARTNER_MARRIED, List.of());
        assertThat(closed).isEqualTo(20); // 52~68 → 15~26
        assertThat(married).isEqualTo(5); // pull 1.0 — 사유가 남지 않는다
    }

    // 차단 한 건이 점프를 발동시키고 상대신호와 접점과 통보온도에서 또 세어지면,
    // 겹친 감점이 대역을 관통해 절대 하한 3에 박힌다 — 상대가 결혼한 판과 같은 자리다.
    @Test
    @DisplayName("하향 점프는 요인이 대역 하한을 뚫고 내려가는 것을 막는다")
    void downwardJumpGivesNoFloorRoom() {
        int score = scorer.apply(BreakupType.RESOLVED, JumpRule.PARTNER_CLOSED, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_UNFAVORABLE), // -10
                factor(FactorName.CONTACT_PATH, FactorLevel.STRONG_UNFAVORABLE),   // -4
                factor(FactorName.NOTICE_TONE, FactorLevel.UNFAVORABLE),           // -3
                factor(FactorName.USER_CONDUCT, FactorLevel.UNFAVORABLE)));        // -4
        assertThat(score).isEqualTo(8); // 대역 8~19의 중앙 13에서 -21, 하한 8에 멈춘다
    }

    // 위쪽은 그대로 연다 — 닫힌 판에서도 진짜 유리한 관측이 나오면 그건 세야 한다.
    @Test
    @DisplayName("하향 점프여도 유리한 요인은 대역 상한 위로 올라갈 수 있다")
    void downwardJumpKeepsCeilingRoom() {
        int score = scorer.apply(BreakupType.RESOLVED, JumpRule.PARTNER_CLOSED, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_FAVORABLE), // +10
                factor(FactorName.CONTACT_PATH, FactorLevel.STRONG_FAVORABLE))); // +4
        assertThat(score).isEqualTo(27); // 13 + 14, 상한 19+10 안
    }

    // pull 1.0은 '결정적'이라는 뜻이라 요인에게 여유도 주지 않는다.
    @Test
    @DisplayName("상대결혼약혼은 유리한 요인이 아무리 쌓여도 대역 8을 못 넘는다")
    void marriedJumpGivesNoHeadroom() {
        int score = scorer.apply(BreakupType.IMPULSIVE, JumpRule.PARTNER_MARRIED, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.CONTACT_PATH, FactorLevel.STRONG_FAVORABLE)));
        assertThat(score).isEqualTo(8);
    }

    @Test
    @DisplayName("최상단 — 최고 점프에 유리 요인을 다 얹어도 89, 96~100(제안 확정 몫)에 닿지 않는다")
    void apply_absoluteCeiling() {
        int score = scorer.apply(BreakupType.IMPULSIVE, JumpRule.PARTNER_HINT, List.of(
                factor(FactorName.PARTNER_SIGNAL, FactorLevel.STRONG_FAVORABLE),
                replacement(FactorLevel.STRONG_FAVORABLE, null),
                factor(FactorName.USER_CONDUCT, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.NOTICE_TONE, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.PARTNER_PATTERN, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.RELATIONSHIP_ASSET, FactorLevel.STRONG_FAVORABLE),
                factor(FactorName.CONTACT_PATH, FactorLevel.STRONG_FAVORABLE)));
        // 62~77의 중앙 69 + 43 = 112 → 상한 77+10. 절대 상한 95는 그 위의 최후 방어선
        assertThat(score).isEqualTo(87);
    }

    // 경계 유형(두 대역의 중간)을 지운 자리. 유형이 대역을 혼자 정하므로 충동형은 언제나
    // 자기 대역 중앙에서 출발한다 — 소진형 쪽으로 절반 끌려가던 완충이 없다.
    @Test
    @DisplayName("유형 - 대역 중앙에서 출발하고 다른 유형이 끌어당기지 않는다")
    void typeAloneSetsBand() {
        int impulsive = scorer.apply(BreakupType.IMPULSIVE, JumpRule.NONE, List.of());
        int burnout = scorer.apply(BreakupType.BURNOUT, JumpRule.NONE, List.of());

        assertThat(impulsive).isEqualTo(60);
        assertThat(burnout).isEqualTo(26);
    }

    // 재회를 막던 조건이 사라진 판. 요인으로는 못 담는다 — 대체자 폭이 4점이라
    // 소진형에서 새 연인이 정리돼도 대역을 못 벗어났다.
    @Test
    @DisplayName("장벽해소 점프 - 같은 사유에서 대역을 통째로 올린다")
    void barrierClearedLiftsBand() {
        int plain = scorer.apply(BreakupType.BURNOUT, JumpRule.NONE, List.of());
        int cleared = scorer.apply(BreakupType.BURNOUT, JumpRule.BARRIER_CLEARED, List.of());

        assertThat(plain).isEqualTo(26);
        assertThat(cleared).isEqualTo(40); // 20~33 → 33~48
    }
}
