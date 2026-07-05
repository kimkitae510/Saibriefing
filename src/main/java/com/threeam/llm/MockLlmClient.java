package com.threeam.llm;

import com.threeam.assessment.service.ReadingLlm;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 실제 LLM 연동 전까지 사용하는 스텁. 고정 응답을 즉시 완료된 future로 돌려주어 API 키, 비용 없이 전체 흐름을 검증한다.
// 실 구현(Gemini)은 llm.provider=gemini 로 두고 별도 빈으로 갈아끼운다.
@Component
@ConditionalOnProperty(name = "llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Override
    public CompletableFuture<String> generate(List<ChatMessage> messages) {
        return CompletableFuture.completedFuture(
                "지금은 많이 힘든 시간일 겁니다. 여기서는 천천히, 하고 싶은 만큼 이야기하셔도 괜찮습니다. "
                        + "(개발용 임시 응답 — 실제 LLM 연동 전 고정 메시지입니다.)");
    }

    // 정밀 판독 호출만 갈라 고정 응답을 돌려준다 — 리포트 화면과 저장 흐름을
    // 키, 비용 없이 검증하기 위한 분기. 2단 파이프라인은 프롬프트 문구로 단을 가린다.
    @Override
    public CompletableFuture<String> generateJsonDeep(List<ChatMessage> messages,
                                                      Map<String, Object> responseSchema) {
        boolean analysisCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.ANALYSIS_ASK));
        if (analysisCall) {
            return CompletableFuture.completedFuture("""
                    (개발용 임시 분석) 이 이별의 핵심은 마지막 다툼이 아니라, 반복된 갈등을 마주 앉아 조율한 경험이 없었다는 데 있다. 두 사람은 만나면 좋았지만, 불편한 문제가 생기면 한쪽은 참았고 한쪽은 피했다.

                    상대의 마음이 사라졌다고 볼 근거는 약하다. 다만 다시 시작해도 같은 일이 반복되리라는 기대가 앞서 있는 상태로 보인다.

                    지금 해야 할 것은 확인성 연락을 멈추고, 2주 뒤 짐 정리를 계기로 짧게 연락하는 것이다. 그 연락에도 응답이 없으면 시도를 멈춘다.""");
        }
        boolean cardCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.CARD_ASK));
        if (cardCall) {
            return CompletableFuture.completedFuture("""
                    {"cards": [
                       {"para": 2, "direction": "DOWN", "pivot": true, "title": "(개발용 임시) 같은 일이 반복되리라는 기대가 판을 내립니다"}
                     ],
                     "reworked": []}""");
        }
        boolean presentCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.PRESENT_ASK));
        if (presentCall) {
            return CompletableFuture.completedFuture(
                    "(개발용 임시 현재화) 당시의 호의는 시간이 지나며 현재형 감정보다 관계의 기억으로 남았을 가능성이 크다. "
                            + "다시 닿으면 익숙함이 먼저 활성화되겠지만, 그것이 연애 감정으로 넘어갈지는 반복 기대가 가른다.");
        }
        boolean criticCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.CRITIC_ASK));
        if (criticCall) {
            return CompletableFuture.completedFuture("PASS");
        }
        boolean synthCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.SYNTH_ASK));
        if (synthCall) {
            return CompletableFuture.completedFuture(
                    "(개발용 임시 종합) 세 판독은 마지막 거절의 무게를 다르게 읽었다. 앞뒤 흐름을 가장 적은 모순으로 설명하는 해석은 "
                            + "마음의 소멸이 아니라 반복 기대의 문제라는 쪽이다. 현재 재회 가능성은 중간쯤에서 반복 기대 해소 여부에 달려 있다.");
        }
        boolean mindCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.MIND_ASK));
        if (mindCall) {
            return CompletableFuture.completedFuture(
                    "(개발용 임시 마음) 이 사람 안에는 남은 정과 마지막 대화의 상처가 같이 있습니다. 시간이 상처는 가라앉혔지만 같은 일이 반복되리라는 생각은 그대로입니다.");
        }
        boolean gradeCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.GRADE_ASK));
        if (gradeCall) {
            return CompletableFuture.completedFuture("MID");
        }
        boolean verdictCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.VERDICT_ASK));
        if (verdictCall) {
            return CompletableFuture.completedFuture("""
                    (개발용 임시 판정) 판정 한 줄: 이 이별은 마음의 소멸이 아니라 반복 기대의 문제입니다.
                    올리는 카드, 남은 호의: 이별 직전까지 관계를 유지하고 호의를 보였습니다 — 마음이 남아 있을 가능성이 여지를 올립니다.
                    내리는 카드, 반복 기대: 확인성 연락에 응답이 없었습니다 — 같은 갈등이 반복되리라는 기대가 여지를 내립니다.""");
        }
        boolean editCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.EDIT_HEADER));
        if (editCall) {
            return CompletableFuture.completedFuture("""
                    {
                      "caseStatus": "POSSIBLE",
                      "gateNote": "",
                      "analysis": [
                        {"subtitle": "(개발용 임시) 이 이별의 핵심은 마지막 다툼이 아니라 조율의 부재입니다", "body": "두 사람은 만나면 좋았지만, 불편한 문제가 생기면 한쪽은 참았고 한쪽은 피했습니다.\\n\\n반복된 갈등을 마주 앉아 조율한 경험이 이 관계에는 없었습니다."}
                      ],
                      "mind": [
                        {"subtitle": "(개발용 임시) 마음이 사라졌다고 볼 근거는 약합니다", "body": "다시 시작해도 같은 일이 반복되리라는 기대가 감정보다 앞서 있는 상태로 보입니다."}
                      ],
                      "answers": [],
                      "action": [
                        {"subtitle": "(개발용 임시) 지금은 확인성 연락을 멈출 때입니다", "body": "2주 뒤 짐 정리를 계기로 짧게 연락하고, 그 연락에도 응답이 없으면 시도를 멈춥니다."}
                      ],
                      "outlookLevel": "LOW",
                      "verdictLine": "(개발용 임시) 마음의 문제가 아니라 반복 기대의 문제라, 그 기대를 바꿀 근거가 확인되기 전까지는 낮게 봅니다.",
                      "verdict": [
                        {"subtitle": "(개발용 임시) 이 이별은 마음의 소멸이 아니라 반복 기대의 문제입니다", "body": "이별 직전까지 호의를 보여, 마음이 남아 있을 가능성이 가능성을 올립니다.", "direction": "UP"},
                        {"subtitle": "(개발용 임시) 다만 반복 기대를 바꿀 근거가 아직 없습니다", "body": "확인성 연락에 응답이 없어, 같은 갈등이 반복되리라는 기대가 가능성을 내립니다.", "direction": "DOWN"}
                      ]
                    }
                    """);
        }
        boolean readingCall = messages.stream()
                .anyMatch(m -> m.content().startsWith(ReadingLlm.PAYLOAD_HEADER));
        if (readingCall) {
            return CompletableFuture.completedFuture("""
                    {
                      "caseStatus": "POSSIBLE",
                      "gateNote": "",
                      "analysisSection": {"title": "이 관계에서 진짜 중요했던 것"},
                      "analysisChapters": [
                        {"title": "(개발용 임시) 위로와 게임으로 나뉘어 보인 싸움은 사실 하나의 충돌이었습니다", "reading": "(개발용 임시) 한쪽에는 연결의 요청이었던 행동이 다른 쪽에는 부담과 침해로 도착했고, 그 번역 오류가 관계 후반 내내 반복됐습니다.", "evidenceIds": ["F01", "F04"]},
                        {"title": "(개발용 임시) 만나면 좋았다는 기억이 일상 운영까지 좋았다는 뜻은 아닙니다", "reading": "(개발용 임시) 좋은 부분과 어려운 부분이 서로 다른 환경에서 나타났고, 반복 문제를 마주 앉아 조율할 기회는 적었습니다.", "evidenceIds": ["F02"]}
                      ],
                      "synthesis": "(개발용 임시) 재회를 막는 핵심은 남은 감정의 양보다 다시 시작해도 같은 갈등이 반복될 것이라는 기대에 있습니다.",
                      "outlookLevel": "LOW",
                      "outlookAnalysis": "(개발용 임시) 이 판이 낮은 것은 감정의 소멸보다 관계 조율에 대한 기대가 꺾인 데 있습니다. 그 기대를 되살릴 행동이 아직 확인되지 않습니다.",
                      "reasons": [
                        {"label": "현재 관계 행동", "direction": "DOWN", "reading": "(개발용 임시) 관계를 다시 여는 움직임보다 종료를 유지하는 행동이 더 분명합니다. 이별 후 두 차례 확인성 연락에 응답이 없었습니다."},
                        {"label": "관계 자산", "direction": "UP", "reading": "(개발용 임시) 과거의 애정 자체를 부정할 근거는 약합니다. 이별 직전까지 관계를 유지하고 호의를 보였습니다."}
                      ],
                      "prologueBlocks": [
                        {"subtitle": "(개발용 임시) 만나면 좋았던 기억은 진짜였습니다", "body": "(개발용 임시) 두 사람은 만나면 좋았고, 서로에게 호의를 놓지 않은 채 이별 직전까지 관계를 지켜왔습니다.\\n\\n다만 좋았던 시간과 별개로, 반복되는 갈등을 마주 앉아 조율한 경험은 적었습니다."},
                        {"subtitle": "(개발용 임시) 지금 가르는 건 남은 감정이 아니라 기대입니다", "body": "(개발용 임시) 이별 후의 연락에 응답이 없다는 사실은 마음의 소멸이 아니라, 다시 시작해도 같은 일이 반복되리라는 기대가 앞서 있다는 뜻으로 읽힙니다."}
                      ],
                      "mind": "(개발용 임시) 좋아했던 마음과 미안함이 남아 있을 수 있지만, 지금은 그 감정보다 이 관계를 다시 조율하러 들어오지 않겠다는 판단이 앞서 있습니다.",
                      "innerVoice": "(개발용 임시) 미안한 마음은 있어. 그런데 다시 시작하면 또 같은 일이 반복될 것 같아.",
                      "answers": [
                        {"question": "(개발용 임시) 아직 저를 좋아하는 걸까요?", "answer": "(개발용 임시) 과거 애정 전체가 없었다고 볼 근거는 약하지만, 현재 확인되는 것은 관계를 다시 여는 행동이 없다는 사실입니다."}
                      ],
                      "action": {
                        "now": "(개발용 임시) 추가 확인성 연락은 멈춥니다.",
                        "why": "(개발용 임시) 지금 문제는 마음 표현의 부족이 아니라 상대가 대화 안으로 들어오지 않는 데 있기 때문입니다.",
                        "nextMove": "(개발용 임시) 2주 뒤 짐 정리를 계기로 짧게 연락합니다.",
                        "timing": "(개발용 임시) 2주 뒤 — 이별 직후의 방어가 가라앉는 시점입니다.",
                        "stopCondition": "(개발용 임시) 그 연락에도 응답이 없으면 시도를 멈춥니다."
                      }
                    }
                    """);
        }
        return generateJson(messages);
    }

    // 분석 흐름 검증용 고정 JSON. 실제 판단은 Gemini가 한다.
    // 유저 발화가 적으면 INSUFFICIENT(데이터 부족)를, 충분하면 POSSIBLE을 돌려줘 두 흐름을 다 확인할 수 있게 한다.
    @Override
    public CompletableFuture<String> generateJson(List<ChatMessage> messages) {
        long userTurns = messages.stream().filter(m -> m.role() == LlmRole.USER).count();
        if (userTurns < 3) {
            return CompletableFuture.completedFuture("""
                    {
                      "verdict": "INSUFFICIENT",
                      "reason": "아직 분석하기엔 이야기가 부족합니다. 어쩌다 헤어졌는지, 지금 연락은 되는지, 상대와 최근 있었던 일을 조금만 더 들려주십시오.",
                      "summary": ""
                    }
                    """);
        }
        return CompletableFuture.completedFuture("""
                {
                  "verdict": "POSSIBLE",
                  "activeReunionOffer": false,
                  "breakupType": "소진형",
                  "typeEvidence": "(개발용 임시) 반복된 다툼 끝에 상대가 지쳐 통보",
                  "userDumpedPartnerLingering": false,
                  "factors": [
                    {"name": "상대신호", "level": "불리", "evidence": "(개발용 임시 근거)", "rationale": "(개발용 임시) 두 달째 무반응이 이어져 불리"},
                    {"name": "대체자", "level": "중립", "evidence": "근거 없음", "rationale": null},
                    {"name": "유저대처", "level": "유리", "evidence": "(개발용 임시 근거)", "rationale": "(개발용 임시) 짧게 마무리하고 연락을 멈춰 유리"},
                    {"name": "통보온도", "level": "중립", "evidence": "근거 없음", "rationale": null},
                    {"name": "상대패턴", "level": "중립", "evidence": "근거 없음", "rationale": null}
                  ],
                  "relapseRisk": {"level": "높음", "reason": "(개발용 임시) 지치게 한 행동의 교정이 확인되지 않음"},
                  "watchFor": [
                    {"point": "상대가 먼저 연락해 오는지", "effect": "오면 상대신호가 유리로 바뀌어 판이 크게 달라짐"}
                  ],
                  "matchProfile": {
                    "reason": "잦은싸움",
                    "subReasons": ["사소한반복", "감정누적"],
                    "dumper": "상대",
                    "fault": "양쪽",
                    "contactState": "무연락",
                    "monthsSinceBreakup": 2,
                    "datingMonths": 18,
                    "ageGroup": null,
                    "gender": null,
                    "repeatBreakup": null,
                    "partnerHasNew": null
                  },
                  "reason": "개발용 임시 분석 — 실제 LLM 연동 전 고정 응답입니다.",
                  "newFacts": ["상대가 먼저 이별을 통보함 (개발용 임시 사실)"]
                }
                """);
    }
}
