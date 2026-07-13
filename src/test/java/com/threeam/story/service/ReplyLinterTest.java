package com.threeam.story.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReplyLinterTest {

    private final ReplyLinter linter = new ReplyLinter();

    @Test
    @DisplayName("판세 어휘 - 조사가 붙어 홀로 쓰인 '판'을 잡는다")
    void catchesBoardWords() {
        assertThat(linter.violatedRules("만나기 전에 판을 정확히 읽으려면")).contains("판세어휘");
        assertThat(linter.violatedRules("주도권이 걔한테 넘어갔어")).contains("판세어휘");
    }

    @Test
    @DisplayName("판세 어휘 - 판단, 재판처럼 다른 낱말의 일부는 잡지 않는다(오탐이 섞이면 지표를 못 믿는다)")
    void ignoresCompoundWords() {
        assertThat(linter.violatedRules("네 판단이 맞아")).isEmpty();
        assertThat(linter.violatedRules("재판까지 갈 일은 아니야")).isEmpty();
        assertThat(linter.violatedRules("간판이 바뀌었대")).isEmpty();
    }

    @Test
    @DisplayName("형식 - 불릿과 마크다운을 잡는다")
    void catchesFormatting() {
        assertThat(linter.violatedRules("- 첫째로 이건")).contains("불릿");
        assertThat(linter.violatedRules("이별·재회 얘기")).contains("불릿");
        assertThat(linter.violatedRules("# 지금 상황 정리")).contains("마크다운");
        assertThat(linter.violatedRules("**중요한 건**")).contains("마크다운");
    }

    @Test
    @DisplayName("양자택일 - 한 문장이든 두 문장으로 쪼갰든 잡는다(프롬프트에서 뺀 규칙을 여기서 감시한다)")
    void catchesEitherOr() {
        assertThat(linter.violatedRules("답장이 오긴 했어? 아니면 그냥 읽고 무시한 상태야?")).contains("양자택일형태");
        assertThat(linter.violatedRules("차단할 거야, 아니면 그대로 둘 거야?")).contains("양자택일형태");
        assertThat(linter.violatedRules("그 뒤로 걔한테서 뭐 온 건 있어?")).isEmpty();
    }

    @Test
    @DisplayName("메모 칸 - 본문만 보고, [답] 마커가 없는 턴은 형식 미준수로 센다")
    void inspectsVisibleAnswerOnly() {
        // 메모에 판세 어휘가 있어도 본문이 깨끗하면 규칙 위반이 아니다
        assertThat(linter.hits("[생각]\n판을 읽는다\n[답]\n그때 뭐라고 했어요?")).isEmpty();
        assertThat(linter.hits("그때 뭐라고 했어요?")).containsOnlyKeys("생각칸없음");
    }

    @Test
    @DisplayName("정상 답변은 아무것도 걸리지 않는다(존댓말, 마침표 포함)")
    void cleanReplyPasses() {
        assertThat(linter.violatedRules(
                "조율 대신 이별을 선택한 것으로 보입니다.\n\n지금은 연락하지 않는 편이 좋겠습니다.")).isEmpty();
    }
}
