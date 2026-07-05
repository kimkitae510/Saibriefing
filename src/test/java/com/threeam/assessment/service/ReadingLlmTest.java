package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReunionVerdict;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReadingLlmTest {

    @Mock
    private com.threeam.llm.LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Assessment saved() {
        return Assessment.builder()
                .storyId(1L)
                .verdict(ReunionVerdict.POSSIBLE)
                .probability(62)
                .reason("판정 총평")
                .factor(AssessmentFactor.of(FactorName.PARTNER_SIGNAL, FactorLevel.UNFAVORABLE,
                        "두 달째 무반응", "무반응이 굳어지는 방향", null))
                .build();
    }

    private ReunionDiagnosis diagnosis(List<ReunionDiagnosis.ReadingFact> facts) {
        return diagnosis(facts, null);
    }

    // 유저 해석이 실린 판 — 오해 교정 축의 조건이다(유형이 아니라 재료가 조건).
    private ReunionDiagnosis diagnosisWithFocus() {
        ReunionDiagnosis base = diagnosis(List.of());
        return new ReunionDiagnosis(base.verdict(), base.activeReunionOffer(), base.breakupType(),
                base.typeEvidence(), base.jumpRule(), base.factors(), base.relapseRisk(),
                base.relapseReason(), base.watchFor(), base.unansweredQuestions(),
                base.matchProfile(), base.relationshipPsychology(), base.reason(), base.newFacts(),
                base.readingFacts(), base.directQuestions(),
                List.of(new ReunionDiagnosis.FocusItem("F01", "프사를 안 내린 건 미련이 남아서다")),
                base.timeEffect(), base.displayDiagnosis());
    }

    private ReunionDiagnosis diagnosis(List<ReunionDiagnosis.ReadingFact> facts,
                                       com.threeam.assessment.dto.RelationshipPsychology psych) {
        ReunionDiagnosis.MatchProfileItem profile = new ReunionDiagnosis.MatchProfileItem(
                null, List.of(), "상대", null, null, null, null, null, null, null, null);
        ReunionDiagnosis.DisplayDiagnosis display = new ReunionDiagnosis.DisplayDiagnosis(
                "이별 후 상대의 태도가 닫히지 않아 다시 판단 중인 판입니다.",
                new ReunionDiagnosis.TimeInsight("시간효과", "지금은 식힐수록 유리한 구간입니다.",
                        "지친 이별이라 자극이 줄면 피로 자체가 옅어집니다."),
                List.of(new ReunionDiagnosis.DisplayItem("partnerSignal", "상대신호",
                        "FACTOR:상대신호", "문을 완전히 닫지는 않았습니다.",
                        "종료 의사를 직접 밝힌 적은 없습니다.", List.of(1))));
        return new ReunionDiagnosis(ReunionVerdict.POSSIBLE, false, null, null, JumpRule.NONE,
                List.of(), null, null, List.of(), List.of(), profile, psych, "총평", List.of(),
                facts, List.of("다시 연락이 올까요?"), List.of(),
                new ReunionDiagnosis.TimeEffect("COOLING_HELPFUL", "2주", "SHORT_COOLING_THEN_RECONTACT",
                        "지친 이별이라 자극이 줄면 회복된다"),
                display);
    }

    // 진단 문장은 1호출이 쓰고 백엔드가 순위와 등급을 붙인 카드다 — 판독은 못 고친다.
    private static final List<ReadingLlm.DiagnosisCard> CARDS = List.of(
            new ReadingLlm.DiagnosisCard("partnerSignal", "상대신호", "CORE", 1, "유리",
                    "CONFIRMED", "문을 완전히 닫지는 않았습니다.", "종료 의사를 직접 밝힌 적은 없습니다.",
                    List.of("F01")),
            new ReadingLlm.DiagnosisCard("breakupReason", "이별사유", "CORE", 2, "매우불리",
                    "CONFIRMED", "지쳐서 끝난 이별입니다.", "누적된 피로가 크게 작용했습니다.",
                    List.of()));

    private ReadingDraft read(String json, List<ReunionDiagnosis.ReadingFact> facts) {
        return read(json, facts, CARDS);
    }

    private ReadingDraft read(String json, List<ReunionDiagnosis.ReadingFact> facts,
                              List<ReadingLlm.DiagnosisCard> cards) {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(json));
        return new ReadingLlm(llmClient, objectMapper, new ReadingProperties(), null, null)
                .read(saved(), diagnosis(facts), null, "MID", cards, List.of()).join();
    }

    // 유효한 v12 JSON 뼈대. 진단 배열은 서버가 덮으므로 일부러 엉뚱한 값을 넣어 둔다.
    private static String validJson(String discoveries) {
        return validJson(discoveries, REGRET_OFF);
    }

    private static String validJson(String discoveries, String regret) {
        return """
                {
                  "diagnosisSummary": "모델이 고쳐 쓴 요약",
                  "diagnosis": [
                    {"key": "partnerSignal", "label": "상대신호", "group": "EXTRA", "rank": 9, "level": "매우불리", "evidenceState": "PARTIAL", "headline": "모델이 고쳐 쓴 판정", "reading": "모델이 고쳐 쓴 근거", "factIds": []}
                  ],
                  "analysisSection": {"title": "이번 이별에서 정말 갈린 것"},
                  "analysisChapters": [%s],
                  "delayedRegretSignal": %s,
                  "actionPlan": {
                    "title": "지금은 어떻게 움직이는 게 나을까?",
                    "stance": "USE_EXISTING_EVENT",
                    "answer": "이미 잡힌 만남을 그대로 씁니다.",
                    "timing": "2주 뒤 예정된 만남",
                    "whyThisTiming": "새 명분을 만들지 않아도 됩니다.",
                    "nextMove": "만남 자리에서 관계 대화를 한 번 꺼낸다",
                    "mindset": "답을 받아내는 자리가 아닙니다.",
                    "goal": "관계 대화의 여지 확인",
                    "decisionValue": "감정적 회피인지, 관계 대화를 닫은 것인지 갈립니다.",
                    "do": ["설득보다 태도로 보여준다"],
                    "stopCondition": "관계 이야기를 피하면 더 밀지 않는다",
                    "ifClosed": "그 반응이면 지금의 선택으로 보고 다음 재접촉 날짜를 만들지 않습니다.",
                    "avoid": ["장문 메시지"]
                  },
                  "chipSeeds": ["만나면 무슨 말부터 해야 할까?"],
                  "currentState": {"feeling": "관계의 의미가 사라졌다고 볼 근거는 약합니다.", "choice": "지금 확인되는 선택은 거리를 유지하는 쪽입니다.", "repairBelief": "가장 약한 것은 다시 해도 달라질 거라는 기대입니다."}
                }
                """.formatted(discoveries, regret);
    }

    private static final String DISCOVERY = """
            {"claimSignature": "연결 가능 여부가 갈등의 기준이었음", "question": "친구도 여행도 괜찮았는데 왜 게임만 그렇게 서운했을까?", "verdict": "게임만 유독 서운했던 건 그때만 연락이 닿을 수 있었기 때문입니다", "reading": "밖에서는 상황이 이유가 되지만, 집에서는 연락할 수 있는데 연결되지 않는 사람으로 체감됩니다.", "role": "INTERACTION", "interpretationId": "U01", "evidenceIds": ["F01"]}
            """;

    private static final String REGRET_OFF =
            """
            {"active": false, "strength": null, "headline": null, "whyNotNow": null, "whyLater": null, "basis": null, "limit": null, "evidenceIds": []}
            """;

    private static final String REGRET_ON =
            """
            {"active": true, "strength": "STRONG", "headline": "지금보다 시간이 지난 뒤 빈자리가 커질 판입니다.", "whyNotNow": "지금은 갈등에서 벗어난 안도가 먼저입니다.", "whyLater": "안도가 옅어지면 매일 있던 연결의 부재가 체감됩니다.", "basis": "어려운 조건에서 3년을 실제로 유지했습니다.", "limit": "그리움과 다시 만나겠다는 선택은 다릅니다.", "evidenceIds": ["F01"]}
            """;

    @Test
    @DisplayName("진단 문장은 1호출 카드로 덮는다 — 판독이 고쳐 써도 화면엔 안 나간다")
    void parse_diagnosisMergedFromCards() {
        ReadingDraft draft = read(validJson(DISCOVERY), List.of());

        assertThat(draft.diagnosisSummary()).isEqualTo("이별 후 상대의 태도가 닫히지 않아 다시 판단 중인 판입니다.");
        assertThat(draft.diagnosis()).hasSize(2);
        assertThat(draft.diagnosis().get(0).headline()).isEqualTo("문을 완전히 닫지는 않았습니다.");
        assertThat(draft.diagnosis().get(0).level()).isEqualTo("유리");   // 모델은 매우불리로 보냈다
        assertThat(draft.diagnosis().get(0).group()).isEqualTo("CORE");   // 모델은 EXTRA로 보냈다
        assertThat(draft.diagnosis().get(0).rank()).isEqualTo(1);          // 모델은 9로 보냈다
        assertThat(draft.timeInsight().headline()).contains("식힐수록");
    }

    @Test
    @DisplayName("행동 계획을 파싱한다 (자세, 시점, 다음 행동, 멈출 조건, 닫혔을 때)")
    void parse_actionPlan() {
        ReadingDraft draft = read(validJson(DISCOVERY), List.of());

        assertThat(draft.actionPlan().stance()).isEqualTo("USE_EXISTING_EVENT");
        assertThat(draft.actionPlan().timing()).isEqualTo("2주 뒤 예정된 만남");
        assertThat(draft.actionPlan().nextMove()).contains("관계 대화를 한 번 꺼낸다");
        assertThat(draft.actionPlan().mindset()).contains("받아내는 자리가 아닙니다");
        assertThat(draft.actionPlan().stopCondition()).contains("더 밀지 않는다");
        assertThat(draft.actionPlan().ifClosed()).contains("재접촉 날짜를 만들지 않습니다");
        assertThat(draft.actionPlan().doList()).containsExactly("설득보다 태도로 보여준다");
    }

    @Test
    @DisplayName("장은 질문과 답 두 칸으로 파싱하고, 끝에 현재 상태 세 축을 싣는다")
    void parse_chapter() {
        ReadingDraft draft = read(validJson(DISCOVERY), List.of());

        ReadingDraft.Chapter chapter = draft.analysisChapters().get(0);
        assertThat(chapter.question()).endsWith("?");
        assertThat(chapter.verdict()).contains("연락이 닿을 수 있었기 때문");
        assertThat(chapter.role()).isEqualTo("INTERACTION");
        assertThat(draft.analysisSectionTitle()).isEqualTo("이번 이별에서 정말 갈린 것");
        assertThat(draft.currentState().repairBelief()).contains("달라질 거라는 기대");
    }

    @Test
    @DisplayName("현재 상태는 세 축이 다 있을 때만 남긴다 — 반쪽 종합은 화면에 올리지 않는다")
    void parse_currentState_partialDropped() {
        String half = validJson(DISCOVERY)
                .replace("\"repairBelief\": \"가장 약한 것은 다시 해도 달라질 거라는 기대입니다.\"",
                        "\"repairBelief\": \"\"");

        assertThat(read(half, List.of()).currentState()).isNull();
    }

    @Test
    @DisplayName("뒤늦은 후회 마크는 근거가 있을 때만 남는다 — 비활성이면 통째로 없다")
    void parse_delayedRegret() {
        assertThat(read(validJson(DISCOVERY), List.of()).delayedRegret()).isNull();

        ReadingDraft on = read(validJson(DISCOVERY, REGRET_ON), List.of());
        assertThat(on.delayedRegret().strength()).isEqualTo("STRONG");
        assertThat(on.delayedRegret().whyLater()).contains("부재가 체감됩니다");
        assertThat(on.delayedRegret().basis()).contains("3년을 실제로 유지");
        assertThat(on.delayedRegret().limit()).contains("선택은 다릅니다");
    }

    @Test
    @DisplayName("활성인데 근거가 비면 마크를 버린다 — 근거 없는 희망만 남기지 않는다")
    void parse_delayedRegret_activeWithoutBasis_dropped() {
        String hollow = """
                {"active": true, "strength": "STRONG", "headline": "언젠가 후회할 겁니다.", "whyNotNow": null, "whyLater": null, "basis": null, "limit": null, "evidenceIds": []}
                """;

        assertThat(read(validJson(DISCOVERY, hollow), List.of()).delayedRegret()).isNull();
    }

    @Test
    @DisplayName("진단 카드가 없으면 리포트가 아니다 — 판독 실패(판정은 유지)")
    void parse_noCards_throws() {
        assertThatThrownBy(() -> read(validJson(DISCOVERY), List.of(), List.of()))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(com.threeam.llm.LlmException.class);
    }

    @Test
    @DisplayName("사전 밖 역할과 자세는 대체값으로 채운다 (salvage 경로 방어)")
    void parse_unknownVocab_fallsBack() {
        String weird = validJson(DISCOVERY)
                .replace("\"role\": \"INTERACTION\"", "\"role\": \"WEIRD\"")
                .replace("\"stance\": \"USE_EXISTING_EVENT\"", "\"stance\": \"WEIRD\"");

        ReadingDraft draft = read(weird, List.of());

        assertThat(draft.analysisChapters().get(0).role()).isEqualTo("TRAJECTORY");
        assertThat(draft.actionPlan().stance()).isEqualTo("HOLD_AND_REASSESS");
    }

    // 2단(편집) 응답 뼈대 — EDIT_SCHEMA와 짝.
    private static final String EDIT_JSON = """
            {
              "caseStatus": "POSSIBLE",
              "gateNote": "",
              "analysis": [
                {"subtitle": "**핵심은** 조율의 부재입니다", "body": "## 배경\\n두 사람은 만나면 좋았습니다.\\n\\n다만 조율의 경험이 적었습니다."},
                {"subtitle": "상대는 관계를 다시 믿어보려 했습니다", "body": "납득은 했지만 마음의 안정은 별개였습니다."}
              ],
              "mind": [
                {"subtitle": "마음보다 판단이 앞서 있습니다", "body": "감정보다 판단이 앞서 있는 상태로 보입니다."}
              ],
              "answers": [
                {"question": "다시 만날 수 있을까요?", "answer": "가능성은 남아 있지만 계기가 필요합니다."}
              ],
              "action": [
                {"subtitle": "지금은 확인성 연락을 멈출 때입니다", "body": "2주 뒤 짐 정리를 계기로 짧게 연락합니다."}
              ],
              "outlookLevel": "HIGH",
              "verdictLine": "이 판에는 마음이 식은 사람이 없습니다.",
              "verdict": [
                {"subtitle": "이 이별은 마음 변화가 아니라 생활 습관의 문제입니다", "body": "이별 직전까지의 호의가 가능성을 올립니다.", "direction": "UP"},
                {"subtitle": "다만 재료가 아직 한마디뿐입니다", "body": "확인된 신호가 적어 가능성을 내립니다.", "direction": "DOWN"}
              ],
              "chips": ["연락은 언제까지 기다려야 할까?", "만나면 무슨 말부터 해야 할까?"]
            }
            """;

    private ReadingLlm.DirectReading readDirect(String analysis, String editJson) {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(analysis),
                        CompletableFuture.completedFuture(editJson));
        return new ReadingLlm(llmClient, objectMapper, new ReadingProperties(), null, null)
                .readDirect(null, null, List.of()).join();
    }

    @Test
    @DisplayName("등급 매퍼가 켜지면 판독문에 판정 등급 한 줄이 붙어 2단으로 간다")
    void readDirect_withGradeMapper() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("자유 분석 원석"),
                        CompletableFuture.completedFuture("판정 원석"),
                        CompletableFuture.completedFuture("MID"),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setVerdictGuide("판정 가이드");
        props.setGradeGuide("등급 가이드");
        ReadingLlm.DirectReading direct = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();

        assertThat(direct.draft().synthesis()).contains("판정 원석");
        assertThat(direct.draft().synthesis()).contains("판정 등급: MID");
    }

    // 분석은 등급을 모르고 쓴다 — 매퍼가 분석문만 받아(사연 없이) 한 줄을 붙인다. 분석이 이미
    // 등급 줄을 썼으면 매퍼는 건너뛴다.
    @Test
    @DisplayName("등급 매퍼: 등급 없는 분석문에 사연 없이 등급 한 줄을 붙이고, 이미 있으면 건너뛴다")
    void readDirect_gradeMapperGradesAnalysisWithoutStory() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("등급 없는 분석 원석"),
                        CompletableFuture.completedFuture("HIGH"),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setGradeGuide("등급 가이드");
        ReadingLlm.DirectReading direct = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();
        assertThat(direct.draft().synthesis()).contains("등급 없는 분석 원석")
                .contains("판정 등급: HIGH");

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(3))
                .generateJsonDeep(captor.capture(), any());
        List<com.threeam.llm.ChatMessage> gradePrompt = captor.getAllValues().get(1);
        assertThat(gradePrompt.get(0).content()).isEqualTo("등급 가이드");
        assertThat(gradePrompt.get(1).content())
                .startsWith(ReadingLlm.GRADE_ASK)
                .contains("등급 없는 분석 원석")
                .doesNotContain("다시 만날 수 있을까요?");

        org.mockito.Mockito.reset(llmClient);
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("분석 원석\n\n판정 등급: MID"),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingLlm.DirectReading again = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();
        assertThat(again.draft().synthesis()).contains("판정 등급: MID");
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(2))
                .generateJsonDeep(anyList(), any());
    }

    // 상대 마음 호출은 사연 + 등급 없는 분석을 받고, 산출은 원석 끝에만 붙는다(화면 문단 파서 밖).
    @Test
    @DisplayName("상대 마음 호출: 사연과 등급 없는 분석을 받아 원석 끝에 [그 사람의 지금 마음과 생각]으로 붙는다")
    void readDirect_mindCallAppendsToRaw() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("분석 원석\n\n판정 등급: MID"),
                        CompletableFuture.completedFuture("그 사람 안에는 정과 상처가 같이 있습니다."),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setMindGuide("마음 가이드");
        props.setKnowledge("지식 한 줄");
        ReadingLlm.DirectReading direct = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();

        assertThat(direct.draft().synthesis())
                .contains("[그 사람의 지금 마음과 생각]\n그 사람 안에는 정과 상처가 같이 있습니다.");
        assertThat(direct.draft().synthesis().indexOf("[그 사람의 지금 마음과 생각]"))
                .isGreaterThan(direct.draft().synthesis().indexOf("판정 등급"));

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(3))
                .generateJsonDeep(captor.capture(), any());
        List<com.threeam.llm.ChatMessage> mindPrompt = captor.getAllValues().get(1);
        assertThat(mindPrompt.get(0).content()).isEqualTo("마음 가이드\n\n[참고 지식]\n지식 한 줄");
        assertThat(mindPrompt.get(1).content())
                .startsWith(ReadingLlm.MIND_ASK)
                .doesNotContain("다시 만날 수 있을까요?")
                .contains("[전문가의 분석]\n분석 원석")
                .doesNotContain("판정 등급");
    }

    @Test
    @DisplayName("품질 심사가 PASS면 판독이 그대로 등급으로 가고 심사문이 원석에 남는다")
    void readDirect_criticPass() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("자유 분석 원석"),
                        CompletableFuture.completedFuture("판정 원석"),
                        CompletableFuture.completedFuture("PASS"),
                        CompletableFuture.completedFuture("MID"),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setVerdictGuide("판정 가이드");
        props.setVerdictCriticGuide("심사 가이드");
        props.setGradeGuide("등급 가이드");
        ReadingLlm.DirectReading direct = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();

        assertThat(direct.draft().synthesis()).contains("[품질 심사]");
        assertThat(direct.draft().synthesis()).contains("판정 원석");
        assertThat(direct.draft().synthesis()).contains("판정 등급: MID");
        assertThat(direct.draft().synthesis()).doesNotContain("[재작성 전 판독]");
    }

    @Test
    @DisplayName("품질 심사가 REVISE면 같은 판정 가이드로 재작성하고, 심사문과 이전 판독을 원석에 보관한다")
    void readDirect_criticRevise() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("자유 분석 원석"),
                        CompletableFuture.completedFuture("얕은 판독"),
                        CompletableFuture.completedFuture("REVISE\n사건 재진술에 그쳤다."),
                        CompletableFuture.completedFuture("재작성 판독"),
                        CompletableFuture.completedFuture("LOW"),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setVerdictGuide("판정 가이드");
        props.setVerdictCriticGuide("심사 가이드");
        props.setVerdictReviseNote("재작성 덧붙임");
        props.setGradeGuide("등급 가이드");
        ReadingLlm.DirectReading direct = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();

        assertThat(direct.draft().synthesis()).contains("[재회 가능성 판정]");
        assertThat(direct.draft().synthesis()).contains("재작성 판독");
        assertThat(direct.draft().synthesis()).contains("[재작성 전 판독]");
        assertThat(direct.draft().synthesis()).contains("얕은 판독");
        assertThat(direct.draft().synthesis()).contains("사건 재진술에 그쳤다");
        assertThat(direct.draft().synthesis()).contains("판정 등급: LOW");
    }

    @Test
    @DisplayName("병렬 판독이 켜지면 독립 가지들을 종합이 최종 판정으로 새로 쓰고, 가지 원석도 함께 보관된다")
    void readDirect_withParallelVerdicts() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("자유 분석 원석"),
                        CompletableFuture.completedFuture("판독A"),
                        CompletableFuture.completedFuture("판독B"),
                        CompletableFuture.completedFuture("판독C"),
                        CompletableFuture.completedFuture("종합 판독"),
                        CompletableFuture.completedFuture("LOW"),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setVerdictGuide("판정 가이드");
        props.setVerdictSamples(3);
        props.setVerdictSynthesisGuide("종합 가이드");
        props.setGradeGuide("등급 가이드");
        ReadingLlm.DirectReading direct = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();

        assertThat(direct.draft().synthesis()).contains("[판독 가지 1]");
        assertThat(direct.draft().synthesis()).contains("판독A");
        assertThat(direct.draft().synthesis()).contains("[판독 가지 3]");
        assertThat(direct.draft().synthesis()).contains("판독C");
        assertThat(direct.draft().synthesis()).contains("[재회 가능성 판정]");
        assertThat(direct.draft().synthesis()).contains("종합 판독");
        assertThat(direct.draft().synthesis()).contains("판정 등급: LOW");
    }

    @Test
    @DisplayName("판정 호출이 켜지면 분석과 판정이 함께 2단으로 가고 원석에 둘 다 보관된다")
    void readDirect_withVerdictCall() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("자유 분석 원석"),
                        CompletableFuture.completedFuture("판정 원석"),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setVerdictGuide("판정 가이드");
        ReadingLlm.DirectReading direct = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();

        assertThat(direct.draft().synthesis()).contains("자유 분석 원석");
        assertThat(direct.draft().synthesis()).contains("[재회 가능성 판정]");
        assertThat(direct.draft().synthesis()).contains("판정 원석");
        assertThat(direct.draft().decision().verdictBlocks()).isNotEmpty();
    }

    @Test
    @DisplayName("2단 편집을 리포트로 매핑한다 — 블록, 마음, 행동, 판정, 카드, 칩, 원석 보관")
    void readDirect_mapsEditedReport() {
        ReadingLlm.DirectReading direct = readDirect("자유 분석 원석", EDIT_JSON);

        ReadingDraft draft = direct.draft();
        ReadingDraft.Decision decision = draft.decision();
        assertThat(direct.caseStatus()).isEqualTo("POSSIBLE");
        assertThat(decision.prologueBlocks()).hasSize(2);
        assertThat(decision.actionBlocks()).hasSize(1);
        assertThat(decision.mindBlocks()).hasSize(1);
        assertThat(decision.mindBlocks().get(0).body()).contains("판단이 앞서");
        assertThat(decision.outlookLevel()).isEqualTo("HIGH");
        assertThat(decision.outlookAnalysis()).isEqualTo("이 판에는 마음이 식은 사람이 없습니다.");
        assertThat(decision.verdictBlocks()).hasSize(2);
        assertThat(decision.verdictBlocks().get(0).subtitle()).contains("생활 습관의 문제");
        assertThat(decision.verdictBlocks().get(0).direction()).isEqualTo("UP");
        assertThat(decision.verdictBlocks().get(1).direction()).isEqualTo("DOWN");
        assertThat(decision.reasons()).isEmpty();
        assertThat(decision.answers()).hasSize(1);
        // 원석은 synthesis 자리에 통째 보관된다 — 재편집과 골든셋 채점의 원본.
        assertThat(draft.synthesis()).isEqualTo("자유 분석 원석");
        assertThat(draft.analysisChapters()).isEmpty();   // 심층 장 폐지
    }

    @Test
    @DisplayName("마크다운 기호는 걷어낸다 — 굵게, 헤더가 화면 문장으로 새지 않는다")
    void readDirect_stripsMarkdown() {
        ReadingDraft.Decision decision = readDirect("원석", EDIT_JSON).draft().decision();

        assertThat(decision.prologueBlocks().get(0).subtitle()).isEqualTo("핵심은 조율의 부재입니다");
        assertThat(decision.prologueBlocks().get(0).body()).doesNotContain("##").doesNotContain("**");
    }

    @Test
    @DisplayName("게이트 판정이면 리포트 없이 상태만 돌려준다")
    void readDirect_gate() {
        String gate = """
                {"caseStatus": "INSUFFICIENT", "gateNote": "이별 경위를 더 들려주세요.",
                 "analysis": [], "mind": [], "answers": [], "action": [],
                 "outlookLevel": "MID", "verdictLine": "", "verdict": [], "chips": []}
                """;
        ReadingLlm.DirectReading direct = readDirect("원석", gate);

        assertThat(direct.caseStatus()).isEqualTo("INSUFFICIENT");
        assertThat(direct.gateNote()).contains("더 들려주세요");
        assertThat(direct.draft()).isNull();
    }

    @Test
    @DisplayName("readingFacts가 비면 요인 근거를 관찰 사실로 승격해 packet에 싣는다(안전망)")
    void packet_fallbackFactsFromFactors() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(validJson(DISCOVERY)));
        new ReadingLlm(llmClient, objectMapper, new ReadingProperties(), null, null)
                .read(saved(), diagnosis(List.of()), null, "MID", CARDS, List.of()).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateJsonDeep(captor.capture(), any());
        String packet = captor.getValue().get(1).content();
        assertThat(packet).contains("\"F01\"").contains("두 달째 무반응");
        assertThat(packet).contains("다시 연락이 올까요?");        // directQuestions 전달
        assertThat(packet).contains("breakupDeclaredBy").contains("상대");
        assertThat(packet).contains("timeEffect").contains("COOLING_HELPFUL");
        // 요인표와 관계심리 판정값(라벨)은 안 실린다 — 실리면 2호출이 요인표를 복창한다
        assertThat(packet).doesNotContain("relationshipPsychology");
    }


    @Test
    @DisplayName("packet 뒤에 coda(판독 직전 명령)가 붙는다 — 조용히 빠지는 사고 방지")
    void packet_appendsCoda() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(validJson(DISCOVERY)));
        ReadingProperties props = new ReadingProperties();
        props.setCoda("[판독 직전 명령] 행동이 애정의 크기를 증언한다.");
        new ReadingLlm(llmClient, objectMapper, props, null, null)
                .read(saved(), diagnosis(List.of()), null, "MID", CARDS, List.of()).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateJsonDeep(captor.capture(), any());
        String userMessage = captor.getValue().get(1).content();
        org.assertj.core.api.Assertions.assertThat(userMessage)
                .endsWith("[판독 직전 명령] 행동이 애정의 크기를 증언한다.");
    }

    // 원문은 packet(JSON) 안이 아니라 그 뒤, coda 앞에 글 모양으로 놓인다 — JSON 문자열로
    // 눌린 원문은 규칙 더미에 묻혀 안 읽히는 게 실측됐다(같은 원문을 글로 받은 외부 모델과의
    // 비교). 이 배치 순서가 조용히 되돌아가면 판독이 다시 요약어로 뜬다.
    @Test
    @DisplayName("packet: 유저 원문이 JSON 밖, coda 앞에 글로 실린다")
    void packet_includesUserMessagesAsPlainTextBeforeCoda() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(validJson(DISCOVERY)));
        ReadingProperties properties = new ReadingProperties();
        properties.setCoda("[판독 직전 명령] 행동이 애정의 크기를 증언한다.");
        new ReadingLlm(llmClient, objectMapper, properties, null, null)
                .read(saved(), diagnosis(List.of()), null, "MID", CARDS,
                        List.of("어제 헤어졌어요", "답장이 없어요")).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateJsonDeep(captor.capture(), any());
        String userMessage = captor.getValue().get(1).content();
        org.assertj.core.api.Assertions.assertThat(userMessage)
                .contains("[대화 원문")
                .contains("어제 헤어졌어요")
                .contains("답장이 없어요");
        // 순서: 원문 표시가 packet(JSON) 뒤에, coda가 원문 뒤에 온다
        int packetEnd = userMessage.lastIndexOf('}');
        int marker = userMessage.indexOf("[대화 원문");
        int coda = userMessage.indexOf("[판독 직전 명령]");
        org.assertj.core.api.Assertions.assertThat(marker).isGreaterThan(packetEnd);
        org.assertj.core.api.Assertions.assertThat(coda).isGreaterThan(marker);
    }

    // 축 라우팅 — 유형이 소진형이면 융합 계열 축이 실리고, 축 설정이 비어 있으면(기존 동작)
    // 블록 자체가 없다. 축은 packet 뒤, 원문 앞이다.
    @Test
    @DisplayName("packet: 소진형이면 축 블록이 packet과 원문 사이에 실린다")
    void packet_includesSelectedAxes() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(validJson(DISCOVERY)));
        ReadingProperties properties = new ReadingProperties();
        properties.setAxes(new java.util.HashMap<>(java.util.Map.of(
                "fusion-root", "갈등이 여러 개로 보이면 뿌리를 가려라.",
                "standard-flip", "기준을 양쪽에 물어라.",
                "outside-inside", "밖의 어려움과 안의 문제는 다른 자산이다.",
                "kept-line", "지킨 선은 칭찬한다.")));
        Assessment burnout = Assessment.builder()
                .storyId(1L).verdict(ReunionVerdict.POSSIBLE).probability(62).reason("판정 총평")
                .breakupType(com.threeam.assessment.entity.BreakupType.BURNOUT)
                .factor(AssessmentFactor.of(FactorName.PARTNER_SIGNAL, FactorLevel.UNFAVORABLE,
                        "두 달째 무반응", "무반응이 굳어지는 방향", null))
                .build();

        new ReadingLlm(llmClient, objectMapper, properties, null, null)
                .read(burnout, diagnosis(List.of()), null, "MID", CARDS, List.of("어제 헤어졌어요")).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateJsonDeep(captor.capture(), any());
        String userMessage = captor.getValue().get(1).content();
        int axis = userMessage.indexOf("[이 판에서 우선 확인할 축");
        org.assertj.core.api.Assertions.assertThat(axis).isGreaterThan(userMessage.lastIndexOf('}') - 1);
        org.assertj.core.api.Assertions.assertThat(userMessage).contains("뿌리를 가려라");
        org.assertj.core.api.Assertions.assertThat(userMessage.indexOf("[대화 원문")).isGreaterThan(axis);
        // 소진형 매핑에 없는 축은 실리지 않는다
        org.assertj.core.api.Assertions.assertThat(userMessage).doesNotContain("지킨 선은 칭찬한다");
    }

    @Test
    @DisplayName("packet: 유저 해석이 있으면 오해 교정 축이 유형 축보다 앞에 실린다")
    void packet_misreadAxisWhenUserFocusPresent() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(validJson(DISCOVERY)));
        ReadingProperties properties = new ReadingProperties();
        properties.setAxes(new java.util.HashMap<>(java.util.Map.of(
                "misread-correction", "유저의 읽기가 사실과 어긋나면 풀어라.",
                "fusion-root", "갈등이 여러 개로 보이면 뿌리를 가려라.")));
        Assessment burnout = Assessment.builder()
                .storyId(1L).verdict(ReunionVerdict.POSSIBLE).probability(62).reason("판정 총평")
                .breakupType(com.threeam.assessment.entity.BreakupType.BURNOUT)
                .factor(AssessmentFactor.of(FactorName.PARTNER_SIGNAL, FactorLevel.UNFAVORABLE,
                        "두 달째 무반응", "무반응이 굳어지는 방향", null))
                .build();

        new ReadingLlm(llmClient, objectMapper, properties, null, null)
                .read(burnout, diagnosisWithFocus(), null, "MID", CARDS, List.of("어제 헤어졌어요")).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateJsonDeep(captor.capture(), any());
        String userMessage = captor.getValue().get(1).content();
        org.assertj.core.api.Assertions.assertThat(userMessage.indexOf("유저의 읽기가 사실과"))
                .isGreaterThan(0)
                .isLessThan(userMessage.indexOf("갈등이 여러 개로"));
    }

    @Test
    @DisplayName("packet: 유저 해석이 없으면 오해 교정 축은 실리지 않는다")
    void packet_noMisreadAxisWithoutUserFocus() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(validJson(DISCOVERY)));
        ReadingProperties properties = new ReadingProperties();
        properties.setAxes(new java.util.HashMap<>(java.util.Map.of(
                "misread-correction", "유저의 읽기가 사실과 어긋나면 풀어라.",
                "size-verdict", "좋아했는지 물으면 크기로 답하라.")));
        Assessment faded = Assessment.builder()
                .storyId(1L).verdict(ReunionVerdict.POSSIBLE).probability(40).reason("판정 총평")
                .breakupType(com.threeam.assessment.entity.BreakupType.FADED)
                .factor(AssessmentFactor.of(FactorName.PARTNER_SIGNAL, FactorLevel.UNFAVORABLE,
                        "두 달째 무반응", "무반응이 굳어지는 방향", null))
                .build();

        new ReadingLlm(llmClient, objectMapper, properties, null, null)
                .read(faded, diagnosis(List.of()), null, "MID", CARDS, List.of("어제 헤어졌어요")).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient).generateJsonDeep(captor.capture(), any());
        String userMessage = captor.getValue().get(1).content();
        org.assertj.core.api.Assertions.assertThat(userMessage).contains("크기로 답하라");
        org.assertj.core.api.Assertions.assertThat(userMessage).doesNotContain("유저의 읽기가 사실과");
    }

    // 어휘 린트 — 금지어가 나오면 1회 재생성하고, 재생성 결과를 쓴다.
    @Test
    @DisplayName("린트: 금지어가 섞이면 한 번 다시 쓰게 하고 재생성 결과를 쓴다")
    void lint_retriesOnceOnBannedWords() {
        String dirty = validJson(DISCOVERY).replace("모델이 고쳐 쓴 요약", "방어기제로 닫힌 요약");
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(dirty))
                .willReturn(CompletableFuture.completedFuture(validJson(DISCOVERY)));

        ReadingDraft draft = new ReadingLlm(llmClient, objectMapper, new ReadingProperties(), null, null)
                .read(saved(), diagnosis(List.of()), null, "MID", CARDS, List.of("어제 헤어졌어요")).join();

        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(2))
                .generateJsonDeep(anyList(), any());
        org.assertj.core.api.Assertions.assertThat(draft).isNotNull();
    }

    // 답 린트 — 판정 기준은 하나다: verdict가 서술문으로 서 있는가.
    @Test
    @DisplayName("답 린트: 목차 꼴(명사구, 물음, ~한 이유)만 걸리고 서술문 답은 통과한다")
    void indexShapedVerdict_catchesTableOfContentsShapes() {
        org.assertj.core.api.Assertions.assertThat(List.of(
                        "친구도 여행도 괜찮았는데 왜 게임만 서운했을까?",
                        "그 사람이 연락을 끊은 진짜 이유",
                        "눈물과 원망",
                        "마지막 통화의 의미",
                        "정말 마음이 식은 걸까"))
                .allMatch(ReadingLlm::indexShapedVerdict);
        org.assertj.core.api.Assertions.assertThat(List.of(
                        "게임만 유독 서운했던 건 그때만 연락이 닿을 수 있었기 때문입니다",
                        "좋아한 것은 진짜였지만 관계를 다시 데울 만큼은 아니었다",
                        "두 갈등의 뿌리는 하나였어요"))
                .noneMatch(ReadingLlm::indexShapedVerdict);
    }

    @Test
    @DisplayName("답 린트: 답이 목차 꼴이면 순번만 알려 1회 재생성한다 — 원문은 지시에 안 싣는다")
    void lint_retriesOnIndexShapedVerdict() {
        String badVerdict = validJson(DISCOVERY).replace(
                "게임만 유독 서운했던 건 그때만 연락이 닿을 수 있었기 때문입니다",
                "그 사람이 연락을 끊은 진짜 이유");
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(badVerdict))
                .willReturn(CompletableFuture.completedFuture(validJson(DISCOVERY)));

        ReadingDraft draft = new ReadingLlm(llmClient, objectMapper, new ReadingProperties(), null, null)
                .read(saved(), diagnosis(List.of()), null, "MID", CARDS, List.of("어제 헤어졌어요")).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(2))
                .generateJsonDeep(captor.capture(), any());
        String retryAsk = captor.getAllValues().get(1).get(2).content();
        org.assertj.core.api.Assertions.assertThat(retryAsk).contains("심층 장 1");
        // 나쁜 꼴을 지시문에 인용하면 그게 다음 출력으로 샌다(실측) — 순번만 넘긴다.
        org.assertj.core.api.Assertions.assertThat(retryAsk).doesNotContain("진짜 이유");
        org.assertj.core.api.Assertions.assertThat(draft.analysisChapters().get(0).verdict())
                .isEqualTo("게임만 유독 서운했던 건 그때만 연락이 닿을 수 있었기 때문입니다");
    }

    @Test
    @DisplayName("답 린트: question이 물음표로 끝나지 않으면 같이 잡는다")
    void lint_retriesWhenQuestionIsNotAsked() {
        String notAsked = validJson(DISCOVERY).replace(
                "친구도 여행도 괜찮았는데 왜 게임만 그렇게 서운했을까?",
                "게임 문제에 대하여");
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(notAsked))
                .willReturn(CompletableFuture.completedFuture(validJson(DISCOVERY)));

        new ReadingLlm(llmClient, objectMapper, new ReadingProperties(), null, null)
                .read(saved(), diagnosis(List.of()), null, "MID", CARDS, List.of("어제 헤어졌어요")).join();

        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(2))
                .generateJsonDeep(anyList(), any());
    }

    @Test
    @DisplayName("인라인 방향 표시 파싱: 올림/내림/미정을 나온 순서대로 UP/DOWN/null로 옮긴다")
    void parsesJudgmentDirectionsInOrder() {
        String analysis = """
                [전문가의 분석]
                이별 직후에도 애착이 살아 있던 문단입니다. (가능성: 올림)

                해부만 있고 표시가 없는 문단은 세지 않습니다.

                무응답이 거절감을 만든 문단입니다. (가능성: 내림)

                현재 연애 여부는 확인되지 않은 문단입니다. (가능성: 미정)

                [재회 가능성 판정]
                판정 문단. (가능성: 내림) 판정 구간의 표기는 세지 않는다.
                """;
        assertThat(ReadingLlm.parseJudgmentDirections(analysis))
                .containsExactly("UP", "DOWN", null);
        assertThat(ReadingLlm.parseJudgmentDirections("표시 없는 구 형식 원석")).isEmpty();
        assertThat(ReadingLlm.parseJudgmentDirections(null)).isEmpty();
    }

    @Test
    @DisplayName("[재회 가능성] 파싱: 제목 뒤 빈 줄 변형도 카드로 살리고 편집 카드를 sol 카드로 대체한다")
    void parsesCardSectionWithBlankLineAfterTitle() {
        String analysis = """
                [전문가의 분석]

                배경 해부 문단입니다.

                [재회 가능성]

                (가능성: 올림, 핵심) 이별 직전까지 애정이 살아 있었습니다

                애정이 살아 있던 흐름을 다룬 본문 문단입니다.

                (가능성: 내림) 현실 문제가 남아 있습니다
                같은 덩이에 붙은 본문 문단입니다.

                (가능성: 미정) 접촉 여부가 판을 가릅니다

                접촉이 확인되면 판이 갈리는 본문 문단입니다.

                맺음 한 문장입니다.

                판정 등급: MID
                """;
        ReadingDraft.Decision decision = readDirect(analysis, EDIT_JSON).draft().decision();

        assertThat(decision.verdictBlocks()).hasSize(3);
        assertThat(decision.verdictBlocks().get(0).subtitle())
                .isEqualTo("이별 직전까지 애정이 살아 있었습니다");
        assertThat(decision.verdictBlocks().get(0).direction()).isEqualTo("UP");
        assertThat(decision.verdictBlocks().get(0).pivot()).isTrue();
        assertThat(decision.verdictBlocks().get(0).body()).contains("애정이 살아 있던 흐름");
        assertThat(decision.verdictBlocks().get(1).direction()).isEqualTo("DOWN");
        assertThat(decision.verdictBlocks().get(1).body()).contains("같은 덩이에 붙은");
        assertThat(decision.verdictBlocks().get(2).direction()).isNull();
        // 등급 줄 앞의 맺음 문장은 마지막 카드 본문에 딸려오지 않는다.
        assertThat(decision.verdictBlocks().get(2).body()).doesNotContain("맺음 한 문장");
    }

    @Test
    @DisplayName("[재회 가능성]이 두 번 출력되면(초안+고친 판) 마지막 섹션의 카드만 집고, 구 이름도 같은 경로로 받는다")
    void parsesLastCardSectionWhenDuplicated() {
        String analysis = """
                [전문가의 분석]

                배경 해부 문단입니다.

                [판을 가른 판단]

                (가능성: 올림) 초안 제목입니다
                초안 본문입니다.

                판정 등급: MID

                [재회 가능성]

                (가능성: 올림) 고친 제목입니다
                고친 본문입니다.

                판정 등급: MID
                """;
        ReadingDraft.Decision decision = readDirect(analysis, EDIT_JSON).draft().decision();

        assertThat(decision.verdictBlocks()).hasSize(1);
        assertThat(decision.verdictBlocks().get(0).subtitle()).isEqualTo("고친 제목입니다");
        assertThat(decision.verdictBlocks().get(0).body()).contains("고친 본문");
    }

    @Test
    @DisplayName("[고친 카드]는 내용이 겹치는 원래 카드를 그 자리에서 대체하고, 안 걸린 카드는 그대로 둔다")
    void appliesFixedCardsInPlace() {
        String analysis = """
                [전문가의 분석]

                배경 해부 문단입니다.

                [재회 가능성]

                (가능성: 올림) 원래 제목입니다
                애정이 살아 있던 흐름을 길게 다룬 본문 문단이라 마흔 자 창 대조가 성립할 만큼 충분히 긴 분량으로 적혀 있습니다.

                (가능성: 내림) 안 걸린 카드 제목입니다
                현실 문제가 무겁게 남아 있다는 본문입니다.

                [고친 카드]

                (가능성: 올림) 고쳐 뽑은 제목입니다
                애정이 살아 있던 흐름을 길게 다룬 본문 문단이라 마흔 자 창 대조가 성립할 만큼 충분히 긴 분량으로 적혀 있습니다. 지금도 그 흐름이 재접촉의 바탕이 됩니다.

                맺음 한 문장입니다.

                판정 등급: MID
                """;
        ReadingDraft.Decision decision = readDirect(analysis, EDIT_JSON).draft().decision();

        assertThat(decision.verdictBlocks()).hasSize(2);
        assertThat(decision.verdictBlocks().get(0).subtitle()).isEqualTo("고쳐 뽑은 제목입니다");
        assertThat(decision.verdictBlocks().get(0).body()).contains("재접촉의 바탕");
        assertThat(decision.verdictBlocks().get(1).subtitle()).isEqualTo("안 걸린 카드 제목입니다");
    }

    @Test
    @DisplayName("고친 판이 초안보다 얇으면(재작성 압축) 본문 총량이 큰 초안을 집는다")
    void keepsRicherCardSectionWhenRewriteCompressed() {
        String analysis = """
                [전문가의 분석]

                배경 해부 문단입니다.

                [재회 가능성]

                (가능성: 올림) 초안 제목입니다
                초안 본문은 판단의 서사와 인과를 길게 담아 원문 그대로 옮겨진 문단이라 분량이 충분히 깁니다.

                판정 등급: MID

                [재회 가능성]

                (가능성: 올림) 고친 제목입니다
                압축된 본문.

                판정 등급: MID
                """;
        ReadingDraft.Decision decision = readDirect(analysis, EDIT_JSON).draft().decision();

        assertThat(decision.verdictBlocks()).hasSize(1);
        assertThat(decision.verdictBlocks().get(0).subtitle()).isEqualTo("초안 제목입니다");
        assertThat(decision.verdictBlocks().get(0).body()).contains("서사와 인과를 길게");
    }

    @Test
    @DisplayName("라벨 문단(원본) + [재회 가능성] 정리본을 합친다 — 정리본이 표시 문단을 맡고, 표시 문단은 02에 안 남는다")
    void mergesInlineLabeledCardsWithSection() {
        String analysis = """
                [전문가의 분석]

                리드 문단입니다.

                (가능성: 올림, 핵심) 애정이 살아 있었습니다
                애정이 확인된 문단이라 원본 그대로 카드가 되는 본문입니다.

                이 문단은 방향이 섞여 그대로 쓸 수 없는 문단이라 아래에서 정리해야 하는 본문이 길게 이어집니다. (아래에서 정리)

                해부만 있어 02 산문으로 남는 문단입니다.

                [재회 가능성]

                (가능성: 내림) 정리된 내림 판단입니다
                이 문단은 방향이 섞여 그대로 쓸 수 없는 문단이라 아래에서 정리해야 하는 본문이 길게 이어집니다. 내림 쪽 문장만 남겨 정리했습니다.

                판정 등급: MID
                """;
        ReadingDraft.Decision decision = readDirect(analysis, EDIT_JSON).draft().decision();

        assertThat(decision.verdictBlocks()).hasSize(2);
        assertThat(decision.verdictBlocks().get(0).subtitle()).isEqualTo("애정이 살아 있었습니다");
        assertThat(decision.verdictBlocks().get(0).direction()).isEqualTo("UP");
        assertThat(decision.verdictBlocks().get(0).pivot()).isTrue();
        assertThat(decision.verdictBlocks().get(1).subtitle()).isEqualTo("정리된 내림 판단입니다");
        assertThat(decision.verdictBlocks().get(1).direction()).isEqualTo("DOWN");
        // 02 산문에는 리드와 해부 문단만 남고, 라벨 문단과 (아래에서 정리) 문단은 없다.
        assertThat(decision.prologueBlocks())
                .extracting(b -> b.body())
                .anyMatch(b -> b.contains("리드 문단"))
                .anyMatch(b -> b.contains("해부만 있어"))
                .noneMatch(b -> b.contains("아래에서 정리"))
                .noneMatch(b -> b.contains("원본 그대로 카드가 되는"));
    }

    private static final String CARD_ANALYSIS = """
            [전문가의 분석]

            리드 문단입니다.

            ## 애정이 살아 있었습니다
            애정이 확인된 문단이라 원본 그대로 카드가 되는 본문입니다.

            방향이 섞여 정리가 필요한 문단이라 아래에서 정리해야 하는 본문이 길게 이어집니다.

            ## 해부 소제목입니다
            해부만 있어 02 산문으로 남는 문단입니다.

            맺음 한 문장입니다.

            판정 등급: MID
            """;

    private ReadingDraft.Decision readWithCardCall(String cardJson) {
        return readWithCardCall(CARD_ANALYSIS, cardJson);
    }

    private String decisionRaw;

    private ReadingDraft.Decision readWithCardCall(String analysis, String cardJson) {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(analysis),
                        CompletableFuture.completedFuture(cardJson),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setCardGuide("카드 가이드");
        ReadingDraft draft = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join()
                .draft();
        decisionRaw = draft.synthesis();
        return draft.decision();
    }

    @Test
    @DisplayName("카드 분류 호출(2호출): 라벨은 번호가 가리키는 원문 문단에 붙고, 원석엔 번호 참조만 남으며, 맺음 문단은 카드가 못 된다")
    void cardCall_labelsOriginalParagraphs() {
        ReadingDraft.Decision decision = readWithCardCall("""
                {"cards": [
                   {"para": 2, "direction": "UP", "pivot": true, "title": "분류가 지은 제목입니다"},
                   {"para": 5, "direction": "DOWN", "pivot": false, "title": "맺음은 카드가 아닙니다"}
                 ],
                 "reworked": [
                   {"para": 3, "cards": [
                     {"direction": "DOWN", "pivot": false, "title": "정리된 내림 판단입니다",
                      "body": "방향이 섞여 정리가 필요한 문단이라 아래에서 정리해야 하는 본문이 길게 이어집니다. 내림 쪽만 남겼습니다."}
                   ]}
                 ]}
                """);

        // reworked(정리본)는 더 쓰지 않는다 — 문단은 원문 그대로, 라벨만 붙는다.
        assertThat(decision.verdictBlocks()).hasSize(1);
        // 소제목은 대목(두 문단)의 제목이라 첫 문단 카드엔 안 붙고 분류가 지은 제목이 남는다.
        assertThat(decision.verdictBlocks().get(0).subtitle()).isEqualTo("분류가 지은 제목입니다");
        assertThat(decision.verdictBlocks().get(0).body())
                .isEqualTo("애정이 확인된 문단이라 원본 그대로 카드가 되는 본문입니다.");
        assertThat(decision.verdictBlocks().get(0).direction()).isEqualTo("UP");
        assertThat(decision.verdictBlocks().get(0).pivot()).isTrue();
        assertThat(decision.prologueBlocks()).extracting(b -> b.body())
                .anyMatch(b -> b.contains("리드 문단"))
                .anyMatch(b -> b.contains("해부만 있어"))
                .noneMatch(b -> b.contains("원본 그대로"));
        // sol의 소제목이 02장 문단 제목이 되고, "## " 줄은 본문에 남지 않는다.
        assertThat(decision.prologueBlocks()).extracting(b -> b.subtitle())
                .anyMatch(s -> s.equals("해부 소제목입니다"));
        assertThat(decision.prologueBlocks()).extracting(b -> b.body())
                .noneMatch(b -> b.contains("##"));
        // 소견서 본문 — 문단 전부가 원문 순서대로(맺음까지), 카드 문단에만 방향.
        List<ReadingDraft.Decision.ReadingBlock> essay = decision.readingBlocks();
        assertThat(essay).extracting(b -> b.body())
                .containsExactly(
                        "리드 문단입니다.",
                        "애정이 확인된 문단이라 원본 그대로 카드가 되는 본문입니다.",
                        "방향이 섞여 정리가 필요한 문단이라 아래에서 정리해야 하는 본문이 길게 이어집니다.",
                        "해부만 있어 02 산문으로 남는 문단입니다.",
                        "맺음 한 문장입니다.");
        assertThat(essay).extracting(b -> b.direction()).containsExactly(null, "UP", null, null, null);
        assertThat(essay.get(1).subtitle()).isEqualTo("애정이 살아 있었습니다");
        assertThat(essay.get(3).subtitle()).isEqualTo("해부 소제목입니다");
        assertThat(essay.get(1).pivot()).isTrue();
        // 원석에는 카드 본문이 복사되지 않고 번호 참조만 남는다 — 같은 문단이 두 번 실리지 않는다.
        String raw = decisionRaw;
        assertThat(raw).contains("(가능성: 올림, 핵심) 분류가 지은 제목입니다\n[2]");
        assertThat(raw.indexOf("원본 그대로 카드가 되는 본문입니다."))
                .isEqualTo(raw.lastIndexOf("원본 그대로 카드가 되는 본문입니다."));
    }

    @Test
    @DisplayName("정리(reworked) 응답은 무시된다 — 문단은 원문 그대로만 선다")
    void cardCall_dropsRewrittenReworkCards() {
        ReadingDraft.Decision decision = readWithCardCall("""
                {"cards": [
                   {"para": 2, "direction": "UP", "pivot": true, "title": "애정이 살아 있었습니다"}
                 ],
                 "reworked": [
                   {"para": 3, "cards": [
                     {"direction": "DOWN", "pivot": false, "title": "압축된 카드입니다",
                      "body": "정리가 필요한 문단을 한 줄로 요약했습니다. 내림 쪽 판단만 새로 썼습니다."}
                   ]}
                 ]}
                """);

        assertThat(decision.verdictBlocks()).hasSize(1);
        assertThat(decision.verdictBlocks().get(0).subtitle()).isEqualTo("애정이 살아 있었습니다");
        assertThat(decision.readingBlocks()).extracting(b -> b.body())
                .anyMatch(b -> b.equals("방향이 섞여 정리가 필요한 문단이라 아래에서 정리해야 하는 본문이 길게 이어집니다."));
        assertThat(decisionRaw).doesNotContain("압축된 카드입니다");
        assertThat(ReadingLlm.keepsSourceSentences(
                "가 나다. 새 맺음 문장입니다.", "가 나다. 라 마.")).isTrue();
        assertThat(ReadingLlm.keepsSourceSentences(
                "새 문장 하나. 새 문장 둘.", "가 나다. 라 마.")).isFalse();
    }

    // 헌법과 참고 지식은 한 시스템 메시지에 순서대로 — 지식이 헌법 앞이나 따로 가면 지시로 읽힌다.
    @Test
    @DisplayName("분석 호출: 참고 지식은 헌법 뒤에 [참고 지식]으로 붙고, 비어 있으면 헌법만 간다")
    void analysisCall_appendsKnowledgeAfterGuide() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(CARD_ANALYSIS),
                        CompletableFuture.completedFuture("{\"cards\": [], \"reworked\": []}"),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setAnalysisGuide("헌법 본문");
        props.setKnowledge("연구 경향 한 줄");
        props.setCardGuide("카드 가이드");
        new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.atLeastOnce())
                .generateJsonDeep(captor.capture(), any());
        String system = captor.getAllValues().get(0).get(0).content();
        assertThat(system).isEqualTo("헌법 본문\n\n[재회 판단의 기준]\n연구 경향 한 줄");

        org.mockito.Mockito.reset(llmClient);
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(CARD_ANALYSIS),
                        CompletableFuture.completedFuture("{\"cards\": [], \"reworked\": []}"),
                        CompletableFuture.completedFuture(EDIT_JSON));
        props.setKnowledge("");
        new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();
        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> again =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.atLeastOnce())
                .generateJsonDeep(again.capture(), any());
        assertThat(again.getAllValues().get(0).get(0).content()).isEqualTo("헌법 본문");
    }

    // 정리표는 분류 호출이 내고 서버가 원석에 [재회 정리]로 적은 뒤 Decision.summary로 다시 읽는다.
    // 분류 호출은 등급 줄을 받아야 "지금 이 등급인 이유"를 쓸 수 있다.
    @Test
    @DisplayName("분류 호출의 정리표가 원석에 적히고 Decision.summary로 읽히며, 분류 프롬프트에 등급 줄이 실린다")
    void cardCall_summaryTable() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("""
                        [전문가의 분석]

                        리드 문단입니다.

                        ## 연락은 끊긴 적이 없었습니다
                        연락이 이어진 문단입니다.

                        마지막 대화에서 크게 다친 문단입니다.

                        맺음 한 문장입니다.

                        판정 등급: MID
                        """),
                        CompletableFuture.completedFuture("""
                        {"cards": [
                           {"para": 2, "direction": "UP", "pivot": true, "title": "연락은 끊긴 적이 없었습니다"}
                         ], "reworked": [],
                         "summary": {"name": "마음이 아직 남아 있는 이별",
                                     "up": [{"keyword": "사랑 확인", "para": 2, "note": "사랑을 확인하고 미래를 말했다"},
                                            {"keyword": "통보 없는 시간 요청", "para": 0, "note": ""}],
                                     "down": [{"keyword": "마지막 대화의 상처", "para": 3, "note": "배려가, 아니었다는 말"}],
                                     "why": "애정은 확인됐지만 상처 뒤의 선택이 아직 안 보입니다."}}
                        """),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setCardGuide("카드 가이드");
        ReadingLlm.DirectReading direct = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();

        ReadingDraft.Decision.Summary summary = direct.draft().decision().summary();
        assertThat(summary.name()).isEqualTo("마음이 아직 남아 있는 이별");
        assertThat(summary.up()).extracting(i -> i.keyword())
                .containsExactly("사랑 확인", "통보 없는 시간 요청");
        assertThat(summary.up()).extracting(i -> i.para()).containsExactly(2, null);
        assertThat(summary.up()).extracting(i -> i.note())
                .containsExactly("사랑을 확인하고 미래를 말했다", "");
        assertThat(summary.down()).extracting(i -> i.keyword()).containsExactly("마지막 대화의 상처");
        // 한 줄에 쉼표가 있어도 항목이 갈라지지 않는다(줄 단위 형식).
        assertThat(summary.down().get(0).note()).isEqualTo("배려가, 아니었다는 말");
        assertThat(summary.why()).isEqualTo("애정은 확인됐지만 상처 뒤의 선택이 아직 안 보입니다.");
        assertThat(direct.draft().synthesis())
                .contains("[재회 정리]\n이름: 마음이 아직 남아 있는 이별\n올리는 것:\n- 사랑 확인 [2] — 사랑을 확인하고 미래를 말했다\n- 통보 없는 시간 요청\n내리는 것:\n- 마지막 대화의 상처 [3] — 배려가, 아니었다는 말\n이유: ");
        // 키워드는 번호가 가리키는 문단에 붙는다 — 2번(라벨 문단)엔 올림 키워드, 3번(라벨 없는 문단)엔
        // 키워드만. 맺음(4번)은 소견서 밖이라 키워드가 가리켜도 붙을 자리가 없다.
        List<ReadingDraft.Decision.ReadingBlock> blocks = direct.draft().decision().readingBlocks();
        assertThat(blocks).extracting(b -> b.keyword())
                .containsExactly(null, "사랑 확인", "마지막 대화의 상처", null);
        assertThat(blocks.get(1).direction()).isEqualTo("UP");
        assertThat(blocks.get(2).direction()).isNull();
        // 정리표는 등급 줄보다 앞, 카드 구획보다 뒤
        String raw = direct.draft().synthesis();
        assertThat(raw.indexOf("[재회 정리]")).isGreaterThan(raw.indexOf("[재회 가능성]"))
                .isLessThan(raw.indexOf("판정 등급"));

        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(3))
                .generateJsonDeep(captor.capture(), any());
        assertThat(captor.getAllValues().get(1).get(1).content()).endsWith("판정 등급: MID");
    }

    // 등급은 분류 호출이 낸다 — 원석에 등급 줄이 없으면 분류 응답의 grade가 줄이 되고, 매퍼는 안 돈다.
    // 분류 시스템 지시 뒤에는 [등급 잣대]가 붙는다.
    @Test
    @DisplayName("분류 호출이 등급까지 낸다 — 등급 줄이 없으면 grade가 판정 등급 줄이 되고 매퍼는 건너뛴다")
    void cardCall_gradesWhenAnalysisHasNoGradeLine() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("""
                        [전문가의 분석]

                        리드 문단입니다.

                        ## 연락은 끊긴 적이 없었습니다
                        연락이 이어진 문단입니다.

                        맺음 한 문장입니다.
                        """),
                        CompletableFuture.completedFuture("""
                        {"cards": [{"para": 2, "direction": "UP", "pivot": true, "title": "연락은 끊긴 적이 없었습니다"}],
                         "summary": {"name": "이름", "up": [], "down": [], "why": "이유"},
                         "grade": "HIGH"}
                        """),
                        CompletableFuture.completedFuture(EDIT_JSON));
        ReadingProperties props = new ReadingProperties();
        props.setCardGuide("카드 가이드");
        props.setGradeGuide("등급 잣대 본문");
        ReadingLlm.DirectReading direct = new ReadingLlm(llmClient, objectMapper, props, null, null)
                .readDirect(null, null, List.of()).join();

        assertThat(direct.draft().synthesis()).endsWith("판정 등급: HIGH");
        assertThat(direct.draft().decision().outlookLevel()).isEqualTo("HIGH");
        ArgumentCaptor<List<com.threeam.llm.ChatMessage>> captor =
                ArgumentCaptor.forClass(List.class);
        // 분석, 분류, 편집 — 매퍼 호출은 없다
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(3))
                .generateJsonDeep(captor.capture(), any());
        assertThat(captor.getAllValues().get(1).get(0).content())
                .isEqualTo("카드 가이드\n\n[등급 잣대]\n등급 잣대 본문");
    }

    @Test
    @DisplayName("문단 하나짜리 대목은 소제목이 그대로 카드 제목이 된다")
    void cardCall_usesHeadingAsTitleForSingleParagraphSection() {
        ReadingDraft.Decision decision = readWithCardCall("""
                [전문가의 분석]

                리드 문단입니다.

                ## 연락은 끊긴 적이 없었습니다
                연락이 이어진 문단이라 원본 그대로 카드가 되는 본문입니다.

                ## 그래도 그쪽 마음은 식어 있었습니다
                식어 있었다는 첫 문단입니다.

                식어 있었다는 둘째 문단입니다.

                맺음 한 문장입니다.

                판정 등급: MID
                """, """
                {"cards": [
                   {"para": 2, "direction": "UP", "pivot": true, "title": "분류가 지은 제목입니다"},
                   {"para": 4, "direction": "DOWN", "pivot": false, "title": "둘째 문단 제목입니다"}
                 ], "reworked": []}
                """);

        assertThat(decision.verdictBlocks()).hasSize(2);
        assertThat(decision.verdictBlocks().get(0).subtitle()).isEqualTo("연락은 끊긴 적이 없었습니다");
        assertThat(decision.verdictBlocks().get(1).subtitle()).isEqualTo("둘째 문단 제목입니다");
    }

    @Test
    @DisplayName("sol이 소제목에 (핵심)을 붙인 판에서는 그 대목의 문단만 핵심이고 luna의 pivot은 무시된다")
    void cardCall_solHeadingPivotOverridesLuna() {
        ReadingDraft.Decision decision = readWithCardCall("""
                [전문가의 분석]

                리드 문단입니다.

                ## 연락은 끊긴 적이 없었습니다
                연락이 이어진 문단입니다.

                ## 그래도 그쪽 마음은 식어 있었습니다 (핵심)
                식어 있었다는 첫 문단입니다.

                식어 있었다는 둘째 문단입니다.

                맺음 한 문장입니다.

                판정 등급: LOW
                """, """
                {"cards": [
                   {"para": 2, "direction": "UP", "pivot": true, "title": "루나가 핵심이라 한 올림"},
                   {"para": 3, "direction": "DOWN", "pivot": false, "title": "식어 있었다는 첫 문단"},
                   {"para": 4, "direction": "DOWN", "pivot": false, "title": "식어 있었다는 둘째 문단"}
                 ], "reworked": []}
                """);

        assertThat(decision.verdictBlocks()).hasSize(3);
        assertThat(decision.verdictBlocks().get(0).subtitle()).isEqualTo("연락은 끊긴 적이 없었습니다");
        assertThat(decision.verdictBlocks().get(0).pivot()).isNull();
        // 소견서 블록은 그 대목 전체에 핵심이 붙고, 소제목에서 괄호는 떼어진다.
        assertThat(decision.readingBlocks()).extracting(b -> b.pivot())
                .containsExactly(null, null, true, true, null);
        assertThat(decision.readingBlocks().get(2).subtitle()).isEqualTo("그래도 그쪽 마음은 식어 있었습니다");
        assertThat(decision.verdictBlocks().get(1).pivot()).isTrue();
        assertThat(decision.verdictBlocks().get(2).pivot()).isTrue();
        // (핵심) 표시는 소제목에서 떼어진다 — 화면 어디에도 괄호가 남지 않는다.
        assertThat(decision.verdictBlocks()).extracting(c -> c.subtitle())
                .noneMatch(s -> s.contains("(핵심)"));
        assertThat(decision.prologueBlocks()).extracting(b -> b.subtitle())
                .noneMatch(s -> s.contains("(핵심)"));
    }

    @Test
    @DisplayName("sol이 소제목에 (높낮이를 가를 수 있는 것)을 붙인 대목은 luna 방향과 무관하게 미정이고, 괄호는 화면에 남지 않는다")
    void hingeHeading_forcesUndecided() {
        ReadingDraft.Decision decision = readWithCardCall("""
                [전문가의 분석]

                리드 문단입니다.

                ## 연락은 끊긴 적이 없었습니다
                연락이 이어진 문단입니다.

                ## 지금 연인이 있는지가 갈라놓습니다 (높낮이를 가를 수 있는 것)
                연인이 있으면 닫히고 없으면 열리는 문단입니다.

                이어지는 둘째 문단입니다.

                맺음 한 문장입니다.

                판정 등급: LOW
                """, """
                {"cards": [
                   {"para": 2, "direction": "UP", "pivot": false, "title": "연락이 이어졌습니다"},
                   {"para": 3, "direction": "DOWN", "pivot": true, "title": "루나가 내림이라 한 문단"}
                 ], "reworked": []}
                """);

        assertThat(decision.verdictBlocks()).hasSize(2);
        assertThat(decision.verdictBlocks().get(1).direction()).isNull();
        assertThat(decision.readingBlocks()).extracting(b -> b.direction())
                .containsExactly(null, "UP", "NONE", "NONE", null);
        assertThat(decision.readingBlocks().get(2).subtitle()).isEqualTo("지금 연인이 있는지가 갈라놓습니다");
        assertThat(decision.readingBlocks()).extracting(b -> b.subtitle())
                .noneMatch(s -> s.contains("가를 수 있는 것"));
        assertThat(decision.verdictBlocks()).extracting(c -> c.subtitle())
                .noneMatch(s -> s.contains("가를 수 있는 것"));
    }

    @Test
    @DisplayName("카드 분류 응답이 깨지면 분석을 그대로 두고 편집 카드로 폴백한다")
    void cardCall_fallsBackWhenBroken() {
        ReadingDraft.Decision decision = readWithCardCall("이건 JSON이 아니다");

        assertThat(decision.verdictBlocks()).hasSize(2);
        assertThat(decision.verdictBlocks().get(0).subtitle()).contains("생활 습관의 문제");
    }

    @Test
    @DisplayName("편집 방향 값: UP/DOWN만 살리고 NONE은 방향 없음(null)으로 비운다")
    void mapsCardDirection() {
        assertThat(ReadingLlm.cardDirection("UP")).isEqualTo("UP");
        assertThat(ReadingLlm.cardDirection("DOWN")).isEqualTo("DOWN");
        assertThat(ReadingLlm.cardDirection("NONE")).isNull();
        assertThat(ReadingLlm.cardDirection("")).isNull();
        assertThat(ReadingLlm.cardDirection(null)).isNull();
    }

    @Test
    @DisplayName("편집본은 문단 수와 소제목이 원문과 같을 때만 받는다 — 분류가 문단 번호를 쓰니까")
    void editKeepsShape_requiresSameParagraphsAndHeadings() {
        String original = "[전문가의 분석]§§여는 문단입니다.§§## 첫 마디§마음이 떠난 것이 아니라 두려움이 컸습니다.§§## 둘째 마디§그래서 멈췄습니다."
                .replace("§", "\n");
        String trimmed = "여는 문단입니다.§§## 첫 마디§두려움이 컸습니다.§§## 둘째 마디§그래서 멈췄습니다."
                .replace("§", "\n");
        String merged = "여는 문단입니다.§§## 첫 마디§두려움이 컸습니다. 그래서 멈췄습니다."
                .replace("§", "\n");
        String renamed = "여는 문단입니다.§§## 첫 번째 마디§두려움이 컸습니다.§§## 둘째 마디§그래서 멈췄습니다."
                .replace("§", "\n");

        assertThat(ReadingLlm.editKeepsShape(original, trimmed)).isTrue();
        assertThat(ReadingLlm.editKeepsShape(original, merged)).isFalse();
        assertThat(ReadingLlm.editKeepsShape(original, renamed)).isFalse();
        assertThat(ReadingLlm.editKeepsShape(original, "")).isFalse();
        assertThat(ReadingLlm.editKeepsShape(original, null)).isFalse();
    }

    @Test
    @DisplayName("결정 호출 없이도 원석의 등급 줄에서 등급을 읽는다 — 없으면 MID")
    void gradeLineLevel_readsRawGradeLine() {
        assertThat(ReadingLlm.gradeLineLevel("본문§§[재회 정리]§이유: x§§판정 등급: HIGH".replace("§", "\n"))).isEqualTo("HIGH");
        assertThat(ReadingLlm.gradeLineLevel("본문§§판정 등급: VERY_LOW".replace("§", "\n"))).isEqualTo("VERY_LOW");
        assertThat(ReadingLlm.gradeLineLevel("본문만")).isEqualTo("MID");
        assertThat(ReadingLlm.gradeLineLevel(null)).isEqualTo("MID");
    }

    @Test
    @DisplayName("편집 출력이 입력 머리말을 따라 써도 [본문] 뒤만 남긴다")
    void stripEditEcho_dropsEchoedHeaders() {
        String echoed = "[사연자가 물은 것]§- 물음§§[본문] 첫 문단입니다.§§둘째 문단입니다.".replace("§", "\n");
        assertThat(ReadingLlm.stripEditEcho(echoed)).isEqualTo("첫 문단입니다.§§둘째 문단입니다.".replace("§", "\n"));
        assertThat(ReadingLlm.stripEditEcho("그냥 본문 **강조**")).isEqualTo("그냥 본문 강조");
    }


    @Test
    void splitSolList_separatesBodyAndLists() {
        String raw = "첫 문단입니다.\n\n둘째 문단입니다.\n\n[올리는 것]\n- 먼저 사랑을 확인했습니다.\n"
                + "- 시간을 정해 두었습니다.\n[내리는 것]\n1. 처음으로 2주를 요청했습니다.\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.body()).isEqualTo("첫 문단입니다.\n\n둘째 문단입니다.");
        assertThat(sl.up()).containsExactly("먼저 사랑을 확인했습니다.", "시간을 정해 두었습니다.");
        assertThat(sl.down()).containsExactly("처음으로 2주를 요청했습니다.");
        assertThat(sl.present()).isTrue();

        ReadingLlm.SolList none = ReadingLlm.splitSolList("본문만 있습니다.");
        assertThat(none.body()).isEqualTo("본문만 있습니다.");
        assertThat(none.present()).isFalse();
    }

    @Test
    void splitSolList_readsGradeWord() {
        String raw = "본문입니다.\n\n[올리는 것]\n- 사랑을 확인했습니다.\n[내리는 것]\n- 2주를 요청했습니다.\n"
                + "[가능성 등급] 매우 낮음\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.body()).isEqualTo("본문입니다.");
        assertThat(sl.grade()).isEqualTo("VERY_LOW");
        assertThat(sl.down()).containsExactly("2주를 요청했습니다.");

        assertThat(ReadingLlm.gradeWord("[가능성 등급]: 높음")).isEqualTo("HIGH");
        assertThat(ReadingLlm.gradeWord("[가능성 등급] 중간")).isNull();
        assertThat(ReadingLlm.gradeWord("[가능성 등급] 모름")).isNull();
        assertThat(ReadingLlm.splitSolList("본문만 있습니다.").grade()).isNull();
    }

    @Test
    void splitSolList_readsNameAndWhy() {
        String raw = "본문입니다.\n\n[이름] 상처로 멈춘 관계\n[총평]\n마음은 남았습니다.\n두려움이 앞섭니다.\n"
                + "[올리는 것]\n- 갑자기 끝난 이별은 다시 움직이는 쪽입니다 — 직전까지 사랑을 확인했습니다.\n"
                + "[내리는 것]\n- 시간 요청은 완곡어일 때가 많습니다 — 다만 상처 직후였습니다.\n[가능성 등급] 높음\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.body()).isEqualTo("본문입니다.");
        assertThat(sl.name()).isEqualTo("상처로 멈춘 관계");
        assertThat(sl.why()).isEqualTo("마음은 남았습니다. 두려움이 앞섭니다.");
        assertThat(sl.up()).hasSize(1);
        assertThat(sl.down()).hasSize(1);
        assertThat(sl.grade()).isEqualTo("HIGH");
        assertThat(sl.present()).isTrue();
    }

    @Test
    void splitSolList_acceptsItemsOnTheMarkerLine() {
        String raw = "본문입니다.\n\n[이름] - 혼자 버티다 끝난 이별\n[총평] - 낮게 봅니다.\n"
                + "[올리는 것] - 첫 올림 — 이 사연\n- 둘째 올림 — 이 사연\n[내리는 것] - 첫 내림 — 이 사연\n[가능성 등급] 낮음\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.name()).isEqualTo("혼자 버티다 끝난 이별");
        assertThat(sl.why()).isEqualTo("낮게 봅니다.");
        assertThat(sl.up()).containsExactly("첫 올림 — 이 사연", "둘째 올림 — 이 사연");
        assertThat(sl.down()).containsExactly("첫 내림 — 이 사연");
        assertThat(sl.grade()).isEqualTo("LOW");
    }

    @Test
    void splitSolList_takesBodyWrittenAfterTheMarkers() {
        String raw = "[이름] 말할 자리를 잃은 이별\n[총평] 높음으로 봅니다.\n두 번째 문장.\n[가능성 등급] 높음\n\n"
                + "## 첫 소제목\n본문 첫 문단.\n\n## 둘째 소제목\n본문 둘째 문단.\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.name()).isEqualTo("말할 자리를 잃은 이별");
        assertThat(sl.why()).isEqualTo("높음으로 봅니다. 두 번째 문장.");
        assertThat(sl.grade()).isEqualTo("HIGH");
        assertThat(sl.body()).isEqualTo("## 첫 소제목\n본문 첫 문단.\n\n## 둘째 소제목\n본문 둘째 문단.");
        assertThat(sl.up()).isEmpty();
    }

    @Test
    void splitSolList_readsTypesAndSummaryRoundTrips() {
        String raw = "본문.\n\n[이름] 이름 한 줄\n[총평] 낮게 봅니다.\n[유형 비교]\n- 누적된 불만 끝의 이별 — 재회가 어려운 쪽\n"
                + "- 순순히 수용한 유형 — 연락은 올 수 있음\n[가능성 등급] 낮음\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.types()).containsExactly("누적된 불만 끝의 이별 — 재회가 어려운 쪽", "순순히 수용한 유형 — 연락은 올 수 있음");
        assertThat(sl.up()).isEmpty();
        assertThat(sl.grade()).isEqualTo("LOW");
        String block = "[재회 정리]\n이름: 이름 한 줄\n유형:\n- 누적된 불만 끝의 이별 — 재회가 어려운 쪽\n- 순순히 수용한 유형\n이유: 낮게 봅니다.";
        ReadingDraft.Decision.Summary summary = ReadingLlm.parseSummary(block);
        assertThat(summary.types()).containsExactly("누적된 불만 끝의 이별 — 재회가 어려운 쪽", "순순히 수용한 유형");
        assertThat(summary.why()).isEqualTo("낮게 봅니다.");
        assertThat(summary.up()).isEmpty();
    }

    @Test
    void splitSolList_placesGradeRelativeListsByGrade() {
        String low = "[이름] 이름\n[가능성 등급] 낮음\n[총평] 낮게 봅니다.\n[이 등급인 이유]\n- 누적형 — 어려운 쪽\n"
                + "[한 칸 더는 아닌 이유]\n- 배신 없음 — 바닥은 아님\n\n## 첫 대목\n본문.\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(low);
        assertThat(sl.grade()).isEqualTo("LOW");
        assertThat(sl.down()).containsExactly("누적형 — 어려운 쪽");
        assertThat(sl.up()).containsExactly("배신 없음 — 바닥은 아님");
        assertThat(sl.why()).isEqualTo("낮게 봅니다.");
        assertThat(sl.body()).isEqualTo("## 첫 대목\n본문.");

        String high = "[이름] 이름\n[가능성 등급] 높음\n[총평] 높게 봅니다.\n[이 등급인 이유] - 살아 있는 채 끝남 — 쉬운 쪽\n"
                + "[한 칸 더는 아닌 이유] - 옆 사람 — 확정 아님\n본문 첫 문단.\n";
        ReadingLlm.SolList sh = ReadingLlm.splitSolList(high);
        assertThat(sh.up()).containsExactly("살아 있는 채 끝남 — 쉬운 쪽");
        assertThat(sh.down()).containsExactly("옆 사람 — 확정 아님");
        assertThat(sh.body()).isEqualTo("본문 첫 문단.");
    }

    @Test
    void splitSolList_readsJudgementItemsAsTypes() {
        String raw = "본문.\n\n[이름] 이름\n[판독]\n- 마지막 상태: 받아들이는 쪽 — 붙잡는 행동이 없었음\n"
                + "- 애정의 잔존: 크기 불명 — 침묵만으로는 못 잼\n[총평] 낮게 봅니다.\n[가능성 등급] 낮음\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.types()).containsExactly("마지막 상태: 받아들이는 쪽 — 붙잡는 행동이 없었음",
                "애정의 잔존: 크기 불명 — 침묵만으로는 못 잼");
        assertThat(sl.why()).isEqualTo("낮게 봅니다.");
        assertThat(sl.grade()).isEqualTo("LOW");
        assertThat(sl.body()).isEqualTo("본문.");
    }

    @Test
    void splitSolList_takesBareBracketLineAsNameWhenMissing() {
        String raw = "본문.\n\n[침묵으로 끝난 불균형한 연애]\n[판독]\n- 마지막 상태: 받아들임 — 붙잡지 않음\n[총평] 낮게.\n[가능성 등급] 낮음\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.name()).isEqualTo("침묵으로 끝난 불균형한 연애");
        assertThat(sl.types()).hasSize(1);
        assertThat(sl.body()).isEqualTo("본문.");
    }

    @Test
    void splitSolList_foldsNumberedJudgementHeadsIntoItems() {
        String raw = "## 첫 대목\n본문.\n\n---\n\n# 재회 가능성 판독\n\n[이름] 대화할 문 앞에서 돌아선 이별\n\n"
                + "[판독 1 — 상대가 그 이별을 원했는지가 중요합니다]  \n첫 문장.\n둘째 문장.\n\n"
                + "[판독 2: 원인이 사건인지 방식인지]\n설명 하나.\n\n[총평]  \n낮게 봅니다.\n\n[가능성 등급] 낮음\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.name()).isEqualTo("대화할 문 앞에서 돌아선 이별");
        assertThat(sl.types()).containsExactly(
                "상대가 그 이별을 원했는지가 중요합니다 — 첫 문장. 둘째 문장.",
                "원인이 사건인지 방식인지 — 설명 하나.");
        assertThat(sl.why()).isEqualTo("낮게 봅니다.");
        assertThat(sl.grade()).isEqualTo("LOW");
        assertThat(sl.body()).isEqualTo("## 첫 대목\n본문.");
    }

    @Test
    void splitSolList_sectionMarkersSeparateBodyFromJudgement() {
        String raw = "[1]\n## 첫 대목\n본문 하나.\n\n## 둘째 대목\n본문 둘.\n\n[2]\n[이름] 짧은 이름\n[판독]\n"
                + "## 소제목이 여기 있어도\n판단 기준: 소견 — 근거\n- 둘째 기준: 소견 — 근거\n[총평] 낮게.\n[가능성 등급] 낮음\n";
        ReadingLlm.SolList sl = ReadingLlm.splitSolList(raw);
        assertThat(sl.body()).isEqualTo("## 첫 대목\n본문 하나.\n\n## 둘째 대목\n본문 둘.");
        assertThat(sl.name()).isEqualTo("짧은 이름");
        assertThat(sl.types()).containsExactly("판단 기준: 소견 — 근거", "둘째 기준: 소견 — 근거");
        assertThat(sl.why()).isEqualTo("낮게.");
        assertThat(sl.grade()).isEqualTo("LOW");
    }
}
