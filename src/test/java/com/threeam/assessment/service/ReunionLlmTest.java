package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.AssessmentProperties;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.RelapseRisk;
import com.threeam.assessment.entity.ReplacementStage;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReunionLlmTest {

    @Mock
    private LlmClient llmClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ReunionDiagnosis diagnose(String json) {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture(json));
        return new ReunionLlm(llmClient, objectMapper, new AssessmentProperties())
                .diagnose(List.of(), List.of(), null, null, null).join();
    }

    @Test
    @DisplayName("정상 JSON을 진단으로 파싱한다 (유형/요인/유지전망/관찰포인트)")
    void parse_success() {
        ReunionDiagnosis diagnosis = diagnose("""
                {
                  "verdict": "POSSIBLE",
                  "activeReunionOffer": false,
                  "breakupType": "소진형",
                  "typeEvidence": "반복 다툼 끝에 지쳐 통보",
                  "userDumpedPartnerLingering": false,
                  "factors": [
                    {"name": "상대신호", "level": "불리", "evidence": "두 달째 무반응", "rationale": "무반응이 굳어지는 방향"},
                    {"name": "대체자", "level": "불리", "evidence": "새 연인 소식", "rationale": "복귀 유인이 사라짐", "stage": "정착"},
                    {"name": "유저대처", "level": "유리", "evidence": "연락 중단", "rationale": "절제가 유지됨"}
                  ],
                  "relapseRisk": {"level": "높음", "reason": "지치게 한 행동의 교정 미확인"},
                  "watchFor": [
                    {"point": "상대의 선연락 여부", "effect": "오면 상대신호가 유리로 바뀜"}
                  ],
                  "reason": "쉽지 않아",
                  "newFacts": ["상대에게 새 연인이 생김"]
                }
                """);

        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(diagnosis.breakupType()).isEqualTo(BreakupType.BURNOUT);
        assertThat(diagnosis.typeEvidence()).isEqualTo("반복 다툼 끝에 지쳐 통보");
        // 요인은 항상 5슬롯 — 응답에 없던 슬롯은 중립(근거 없음)으로 채워진다
        assertThat(diagnosis.factors()).hasSize(7); // 고정 7슬롯(누락은 중립 채움)
        assertThat(diagnosis.factors().get(0).level()).isEqualTo(FactorLevel.UNFAVORABLE);
        assertThat(diagnosis.factors().get(1).stage()).isEqualTo(ReplacementStage.SETTLED);
        assertThat(diagnosis.factors().get(3).level()).isEqualTo(FactorLevel.NEUTRAL);
        assertThat(diagnosis.factors().get(3).evidence()).isEqualTo(ReunionLlm.NO_EVIDENCE);
        assertThat(diagnosis.relapseRisk()).isEqualTo(RelapseRisk.HIGH);
        assertThat(diagnosis.watchFor()).hasSize(1);
        assertThat(diagnosis.watchFor().get(0).point()).isEqualTo("상대의 선연락 여부");
    }

    @Test
    @DisplayName("슬롯 밖 요인과 3단계 밖 판정은 버리고, 같은 슬롯 중복은 첫 판정만 남긴다")
    void parse_normalizesFactors() {
        ReunionDiagnosis diagnosis = diagnose("""
                {
                  "verdict": "POSSIBLE",
                  "activeReunionOffer": false,
                  "breakupType": "충동형",
                  "userDumpedPartnerLingering": false,
                  "factors": [
                    {"name": "도덕성", "level": "불리", "evidence": "슬롯 밖", "rationale": "버려짐"},
                    {"name": "상대신호", "level": "애매함", "evidence": "판정 밖", "rationale": "버려짐"},
                    {"name": "상대신호", "level": "유리", "evidence": "먼저 연락 옴", "rationale": "첫 판정"},
                    {"name": "상대신호", "level": "불리", "evidence": "중복", "rationale": "무시됨"}
                  ],
                  "reason": ""
                }
                """);

        assertThat(diagnosis.factors()).hasSize(7); // 고정 7슬롯(누락은 중립 채움)
        assertThat(diagnosis.factors().get(0).name()).isEqualTo(FactorName.PARTNER_SIGNAL);
        assertThat(diagnosis.factors().get(0).level()).isEqualTo(FactorLevel.FAVORABLE);
        assertThat(diagnosis.factors().get(0).evidence()).isEqualTo("먼저 연락 옴");
    }

    @Test
    @DisplayName("유형이 없어도 POSSIBLE을 유지한다 — 판은 확률 대역이 아니라 결정 호출이 만든다")
    void parse_missingType_staysPossible() {
        ReunionDiagnosis diagnosis = diagnose("""
                {"verdict": "POSSIBLE", "activeReunionOffer": false,
                 "userDumpedPartnerLingering": false, "factors": [], "reason": ""}
                """);

        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(diagnosis.breakupType()).isNull();
    }

    @Test
    @DisplayName("활성 재회 제안이면 유형이 없어도 강등하지 않는다(확률은 100으로 확정될 경로)")
    void parse_missingTypeButActiveOffer_staysPossible() {
        ReunionDiagnosis diagnosis = diagnose("""
                {"verdict": "POSSIBLE", "activeReunionOffer": true,
                 "userDumpedPartnerLingering": false, "factors": [], "reason": ""}
                """);

        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(diagnosis.activeReunionOffer()).isTrue();
    }

    @Test
    @DisplayName("관찰 포인트는 최대 2개, 빈 항목은 버린다")
    void parse_watchForCappedAndFiltered() {
        ReunionDiagnosis diagnosis = diagnose("""
                {"verdict": "POSSIBLE", "activeReunionOffer": false, "breakupType": "충동형",
                 "userDumpedPartnerLingering": false, "factors": [], "reason": "",
                 "watchFor": [
                   {"point": "하나", "effect": "효과1"},
                   {"point": "", "effect": "빈 point는 버려짐"},
                   {"point": "둘", "effect": "효과2"},
                   {"point": "셋", "effect": "상한 초과로 버려짐"}
                 ]}
                """);

        assertThat(diagnosis.watchFor()).hasSize(2);
        assertThat(diagnosis.watchFor().get(1).point()).isEqualTo("둘");
    }

    @Test
    @DisplayName("newFacts를 파싱한다 — 빈 문자열은 버리고, 정상 범위의 개수는 자르지 않는다")
    void parse_newFacts() {
        ReunionDiagnosis diagnosis = diagnose("""
                {"verdict": "POSSIBLE", "activeReunionOffer": false, "breakupType": "충동형",
                 "userDumpedPartnerLingering": false, "factors": [], "reason": "",
                 "newFacts": ["사실1", "", "사실2", "사실3", "사실4", "사실5", "사실6"]}
                """);

        // 중요한 사실을 개수로 자르지 않는다(빈 문자열만 제거) — 원장 무상한 정책과 한 몸
        assertThat(diagnosis.newFacts())
                .containsExactly("사실1", "사실2", "사실3", "사실4", "사실5", "사실6");
    }

    @Test
    @DisplayName("newFacts 폭주 방어 — 안전핀(20개)을 넘는 이상 응답만 잘라낸다")
    void parse_newFacts_runawayCapped() {
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            items.append(i > 1 ? "," : "").append("\"사실").append(i).append("\"");
        }
        String json = "{\"verdict\": \"POSSIBLE\", \"activeReunionOffer\": false,"
                + " \"breakupType\": \"충동형\", \"userDumpedPartnerLingering\": false,"
                + " \"factors\": [], \"reason\": \"\", \"newFacts\": [" + items + "]}";

        ReunionDiagnosis diagnosis = diagnose(json);

        assertThat(diagnosis.newFacts()).hasSize(20).startsWith("사실1").endsWith("사실20");
    }

    @Test
    @DisplayName("알 수 없는 verdict와 유형은 안전한 기본값으로 떨어진다")
    void parse_unknownEnum_fallsBack() {
        ReunionDiagnosis diagnosis = diagnose("""
                {"verdict": "???", "breakupType": "이상한유형",
                 "factors": [], "reason": ""}
                """);

        // verdict 기본값 POSSIBLE. 유형 미상 강등 경로는 확률 대역과 함께 제거됐다.
        assertThat(diagnosis.verdict()).isEqualTo(ReunionVerdict.POSSIBLE);
        assertThat(diagnosis.activeReunionOffer()).isFalse(); // 필드 누락 시 안전한 기본값
        assertThat(diagnosis.breakupType()).isNull();
    }

    @Test
    @DisplayName("관계 심리 - 라벨을 검증해 파싱하고, 사전 밖 라벨은 그 축만 버린다")
    void parse_relationshipPsychology() {
        ReunionDiagnosis diagnosis = diagnose("""
                {
                  "verdict": "POSSIBLE",
                  "activeReunionOffer": false,
                  "breakupType": "소진형",
                  "factors": [],
                  "relationshipPsychology": {
                    "attachment": {
                      "user": {"label": "불안형", "confidence": "중간"},
                      "partner": {"label": "회피 성향", "confidence": "높음"},
                      "description": "멀어질수록 확인하려 했고 상대는 거리를 뒀음"
                    },
                    "interactionPattern": {"label": "추구-회피", "confidence": "높음",
                      "description": "확인할수록 물러나고 물러날수록 더 확인하는 반복"},
                    "needConflict": {"left": "연결감", "right": "자율성",
                      "description": "연결을 확인하는 행동이 상대의 혼자 시간을 침해함"}
                  },
                  "reason": ""
                }
                """);

        var psychology = diagnosis.relationshipPsychology();
        assertThat(psychology).isNotNull();
        assertThat(psychology.attachment().user().label()).isEqualTo("불안형");
        assertThat(psychology.attachment().user().confidence()).isEqualTo("중간");
        assertThat(psychology.attachment().partner()).isNull(); // "회피 성향"은 사전 밖 — 그 축만 버림
        assertThat(psychology.interactionPattern().label()).isEqualTo("추구-회피");
        assertThat(psychology.needConflict().left()).isEqualTo("연결감");
        assertThat(psychology.needConflict().right()).isEqualTo("자율성");
    }

    @Test
    @DisplayName("관계 심리 - 세 축이 전부 비면 null로 접는다 (라벨 없는 attachment는 축이 아니다)")
    void parse_relationshipPsychologyEmpty() {
        ReunionDiagnosis diagnosis = diagnose("""
                {"verdict": "POSSIBLE", "activeReunionOffer": false, "breakupType": "충동형",
                 "factors": [],
                 "relationshipPsychology": {"attachment": {"description": "라벨 없이 설명만"}},
                 "reason": ""}
                """);

        assertThat(diagnosis.relationshipPsychology()).isNull();
    }

    @Test
    @DisplayName("깨진 JSON은 LlmException으로 실패한다 — LLM 재호출(자동 재시도) 없이")
    void parse_malformed_throws() {
        given(llmClient.generateJsonDeep(anyList(), any()))
                .willReturn(CompletableFuture.completedFuture("이건 JSON이 아니야"));
        ReunionLlm reunionLlm = new ReunionLlm(llmClient, objectMapper, new AssessmentProperties());

        assertThatThrownBy(() -> reunionLlm.diagnose(List.of(), List.of(), null, null, null).join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(LlmException.class);
        // 자동 재시도 금지 — 실패마다 진단 1회분이 2배 과금된다. 재시도는 유저 버튼 몫.
        org.mockito.Mockito.verify(llmClient, org.mockito.Mockito.times(1)).generateJsonDeep(anyList(), any());
    }

}
