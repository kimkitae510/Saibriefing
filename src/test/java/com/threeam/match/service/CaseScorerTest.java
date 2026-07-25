package com.threeam.match.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.threeam.match.entity.ReunionCase;
import com.threeam.match.entity.StoryMatchProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

class CaseScorerTest {

    private final CaseScorer scorer = new CaseScorer();

    private StoryMatchProfile profile(String reason, String subReasons) {
        return StoryMatchProfile.builder()
                .storyId(1L)
                .reason(reason)
                .subReasons(subReasons)
                .build();
    }

    private ReunionCase reunionCase(String reason, String subReasons) {
        ReunionCase target = BeanUtils.instantiateClass(ReunionCase.class);
        ReflectionTestUtils.setField(target, "id", 1L);
        ReflectionTestUtils.setField(target, "reason", reason);
        ReflectionTestUtils.setField(target, "subReasons",
                subReasons == null ? null : Arrays.asList(subReasons.split(",")));
        return target;
    }

    @Test
    @DisplayName("서브태그 - 주끼리 같은 쪽이 주와 부가 엇갈린 쪽보다 높다")
    void primaryMatchOutranksCrossMatch() {
        StoryMatchProfile mine = profile("본인과실", "질투의심,무심소홀");

        int bothPrimary = scorer.score(mine, reunionCase("본인과실", "질투의심"));
        int crossed = scorer.score(mine, reunionCase("본인과실", "무심소홀,질투의심"));

        // 방아쇠가 같은 사례가 배경만 겹치는 사례보다 실제로 더 닮았다.
        assertThat(bothPrimary).isGreaterThan(crossed);
    }

    @Test
    @DisplayName("서브태그 - 부끼리만 겹치면 주끼리 겹칠 때보다 크게 낮다")
    void secondaryOnlyScoresLowest() {
        StoryMatchProfile mine = profile("성격차이", "감정처리,갈등회피");

        int primary = scorer.score(mine, reunionCase("성격차이", "감정처리"));
        int secondaryOnly = scorer.score(mine, reunionCase("성격차이", "계획즉흥,갈등회피"));

        assertThat(primary).isGreaterThan(secondaryOnly);
    }

    @Test
    @DisplayName("서브태그 - 주와 부가 모두 겹치면 주만 겹칠 때보다 높다(겹침을 합산한다)")
    void overlapsAccumulate() {
        StoryMatchProfile mine = profile("본인과실", "질투의심,무심소홀");

        int both = scorer.score(mine, reunionCase("본인과실", "질투의심,무심소홀"));
        int primaryOnly = scorer.score(mine, reunionCase("본인과실", "질투의심,술버릇"));

        assertThat(both).isGreaterThan(primaryOnly);
    }

    @Test
    @DisplayName("대분류만 같고 서브태그가 전부 어긋나면 임계에 못 미친다")
    void reasonAloneIsNotSimilarEnough() {
        int score = scorer.score(profile("장거리", "거리지침"), reunionCase("장거리", "시차"));

        assertThat(score).isLessThan(CaseScorer.MIN_SCORE);
    }

    @Test
    @DisplayName("dumper가 미상끼리 맞은 건 닮은 것으로 세지 않는다")
    void unknownDumperIsNotSimilarity() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("미상").dumper("미상").build();
        ReunionCase target = reunionCase("미상", null);
        ReflectionTestUtils.setField(target, "dumper", "미상");

        // 대분류(30)만 인정되고 dumper는 빠진다 — 둘 다 모른다는 건 공통점이 아니다.
        assertThat(scorer.score(mine, target)).isEqualTo(30);
    }

    @Test
    @DisplayName("떠밀린 통보는 진짜 자기가 찬 사례와 매칭되지 않는다(방향 게이트)")
    void pushedDumperIsOpposedToGenuineSelfDumper() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("애정식음").subReasons("마음변함").dumper("나떠밀림").build();
        ReunionCase target = reunionCase("애정식음", "마음변함");
        ReflectionTestUtils.setField(target, "dumper", "나");

        // 태그가 아무리 겹쳐도 겪는 자리가 반대면 남의 이야기다.
        assertThat(scorer.score(mine, target)).isZero();
    }

    @Test
    @DisplayName("떠밀린 통보는 상대 통보 사례와 같은 쪽으로 가점된다(정확 일치보다는 낮게)")
    void pushedDumperIsKindredToPartnerDumper() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("애정식음").dumper("나떠밀림").build();

        ReunionCase partner = reunionCase("애정식음", null);
        ReflectionTestUtils.setField(partner, "dumper", "상대");
        ReunionCase exact = reunionCase("애정식음", null);
        ReflectionTestUtils.setField(exact, "dumper", "나떠밀림");
        ReunionCase silent = reunionCase("애정식음", null);

        int partnerScore = scorer.score(mine, partner);
        int exactScore = scorer.score(mine, exact);
        int silentScore = scorer.score(mine, silent);

        assertThat(partnerScore).isGreaterThan(silentScore);
        assertThat(exactScore).isGreaterThan(partnerScore);
        assertThat(scorer.matchedTags(mine, partner)).contains("이별을 원한 쪽");
    }

    @Test
    @DisplayName("소재가 같은 사례가 이긴다 — 범용 태그(거짓말신뢰)만 겹치는 사례를 대분류 교차가 넘는다")
    void concreteTopicOutranksGenericTagMatch() {
        // 대분류가 갈려 30점을 잃은 쪽이 범용 태그 주-주 일치만 겹친 사례에 밀리면 안 된다.
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("본인과실").subReasons("거짓말신뢰,소개팅앱,감정쓰레기통")
                .dumper("상대").fault("나").contactState("차단").build();

        ReunionCase money = reunionCase("본인과실", "거짓말신뢰,돈문제");
        ReflectionTestUtils.setField(money, "dumper", "상대");
        ReflectionTestUtils.setField(money, "fault", "나");
        ReflectionTestUtils.setField(money, "contactState", "연락중");

        ReunionCase dating = reunionCase("외도", "소개팅앱,거짓말신뢰");
        ReflectionTestUtils.setField(dating, "dumper", "상대");
        ReflectionTestUtils.setField(dating, "fault", "나");
        ReflectionTestUtils.setField(dating, "contactState", "차단");

        // 대분류가 갈렸어도 소개팅앱이 외도를 가리켜 교차 점수를 받고, 범용 태그로 이긴 사례를 넘는다.
        assertThat(scorer.score(mine, dating)).isGreaterThan(scorer.score(mine, money));
        // 태그는 정확 일치에만 붙인다 — 교차는 점수로만 반영된다.
        // 거짓말신뢰는 과실 행동 풀이라 값을 안 띄우고 축 이름으로 떨어지고, 차단은 값 그대로 뜬다.
        assertThat(scorer.matchedTags(mine, dating))
                .contains("이별의 결정적 계기", "헤어지자고 한 쪽", "차단")
                .doesNotContain("이별 사유", "거짓말신뢰");
    }

    @Test
    @DisplayName("범용 태그의 방아쇠 일치는 구체 태그의 방아쇠 일치보다 낮다")
    void genericPrimaryScoresLower() {
        int generic = scorer.score(profile("본인과실", "거짓말신뢰"),
                reunionCase("본인과실", "거짓말신뢰"));
        int concrete = scorer.score(profile("본인과실", "도박빚"),
                reunionCase("본인과실", "도박빚"));

        assertThat(concrete).isGreaterThan(generic);
    }

    @Test
    @DisplayName("이별 경과는 버킷으로 본다 - 한 달 안끼리는 같게, 한 달과 넉 달은 다르게")
    void periodComparedByBucket() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("잦은싸움").monthsSinceBreakup(1).build();

        ReunionCase near = reunionCase("잦은싸움", null);
        ReflectionTestUtils.setField(near, "monthsSinceBreakup", 1);
        ReunionCase far = reunionCase("잦은싸움", null);
        ReflectionTestUtils.setField(far, "monthsSinceBreakup", 4);

        assertThat(scorer.score(mine, near)).isGreaterThan(scorer.score(mine, far));
    }

    // 감정누적이 잦은싸움 갈래에 있으면, 싸운 적 없는 사연이 그 태그 하나로 싸움 이별 계열이 된다.
    @Test
    @DisplayName("감정누적은 범용이라 잦은싸움 사례를 끌어올리지 못한다")
    void accumulatedFeelingIsGeneric() {
        StoryMatchProfile mine = profile("가치관차이", "감정누적,갈등회피");

        int fightCase = scorer.score(mine, reunionCase("잦은싸움", "감정누적,사소한반복"));
        int avoidCase = scorer.score(mine, reunionCase("성격차이", "갈등회피,감정처리"));

        // 갈등회피가 겹치는 쪽이 감정누적만 겹치는 싸움 사례보다 닮았다.
        assertThat(avoidCase).isGreaterThan(fightCase);
    }

    // 상대에게 새 연인이 있는 판은 사유가 같아도 넘어야 할 조건이 하나 더 있어,
    // 같은 처지의 사례가 먼저 와야 한다.
    @Test
    @DisplayName("상대에게 새 사람이 있으면 같은 처지의 사례가 위로 온다")
    void partnerHasNewLiftsSameSituation() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("본인과실").subReasons("술버릇")
                .partnerHasNew(true).build();

        ReunionCase withNew = reunionCase("본인과실", "술버릇");
        ReflectionTestUtils.setField(withNew, "partnerHasNew", true);
        ReunionCase withoutNew = reunionCase("본인과실", "술버릇");

        assertThat(scorer.score(mine, withNew)).isGreaterThan(scorer.score(mine, withoutNew));
    }

    // 둘 다 아닌 경우가 대다수라 변별력이 없다 — 온오프와 같은 문법으로 한쪽만이면 가점 없음.
    @Test
    @DisplayName("사연에만 새 사람이 있으면 가점이 없다")
    void partnerHasNewNeedsBothSides() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("본인과실").subReasons("술버릇")
                .partnerHasNew(true).build();
        StoryMatchProfile plain = StoryMatchProfile.builder()
                .storyId(1L).reason("본인과실").subReasons("술버릇").build();

        ReunionCase target = reunionCase("본인과실", "술버릇");

        assertThat(scorer.score(mine, target)).isEqualTo(scorer.score(plain, target));
    }

    // 재회한 사례는 결과가 연락 상태를 정해버려 값이 전부 연락중이다. 그걸 채점하면
    // 무연락, 차단인 유저에게 성공 사례가 통째로 밀리기만 하고 가려지는 건 없다.
    @Test
    @DisplayName("성공 사례의 연락 상태는 겹쳐도 점수와 태그에 안 실린다")
    void contactStateIgnoredForReunitedCase() {
        StoryMatchProfile mine = StoryMatchProfile.builder()
                .storyId(1L).reason("본인과실").subReasons("술버릇")
                .contactState("연락중").build();

        ReunionCase reunited = reunionCase("본인과실", "술버릇");
        ReflectionTestUtils.setField(reunited, "contactState", "연락중");
        ReflectionTestUtils.setField(reunited, "outcome", "성공");

        ReunionCase failed = reunionCase("본인과실", "술버릇");
        ReflectionTestUtils.setField(failed, "contactState", "연락중");
        ReflectionTestUtils.setField(failed, "outcome", "실패");

        assertThat(scorer.score(mine, reunited)).isLessThan(scorer.score(mine, failed));
        assertThat(scorer.matchedTags(mine, reunited)).doesNotContain("연락 중");
        assertThat(scorer.matchedTags(mine, failed)).contains("연락 중");
    }
}
