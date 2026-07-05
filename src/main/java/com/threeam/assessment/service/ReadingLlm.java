package com.threeam.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.ReadingProperties;
import com.threeam.assessment.dto.ReadingDraft;
import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReadingVocab;
import com.threeam.llm.ChatMessage;
import com.threeam.llm.LlmClient;
import com.threeam.llm.LlmException;
import com.threeam.llm.LlmJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// 정밀 판독(2호출) 담당 — v11.1 계약.
// 진단 문장은 1호출이 확정한다. 판독은 그걸 복사만 하고, 서버는 아예 입력값으로 되돌려
// 병합한다(복사도 안 믿는다 — 모델이 한 글자라도 고치면 화면과 판정이 갈린다).
// 판독이 실제로 쓰는 것은 심층 장, 행동 계획, 후속 칩이다.
// 입력(ReadingPacket)에 요인표, 점프, 관계심리 판정값은 싣지 않는다 — 전부 넘기면
// 2호출이 요인표를 자연어로 복창하는 경향이 실측됐다.
// 판독 지시 전문은 서비스 자산이라 소스에 두지 않고 ReadingProperties(로컬 reading.yml)로 주입받는다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingLlm {

    // packet 블록의 머리 문구. MockLlmClient가 이 문구로 판독 호출을 식별한다(개발 스텁 분기).
    public static final String PAYLOAD_HEADER =
            "확정 판정 ReadingPacket(이번 리포트의 유일한 사례 데이터 — 여기 없는 사실을 만들지 마라):";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final ReadingProperties readingProperties;
    private final com.threeam.llm.AnthropicMessagesClient anthropicClient;
    private final com.threeam.llm.OpenAiResponsesClient openAiClient;

    public CompletableFuture<ReadingDraft> read(Assessment saved, ReunionDiagnosis diagnosis,
                                                String intakeBlock, String level,
                                                List<DiagnosisCard> cards,
                                                List<String> userMessages) {
        return read(saved, diagnosis, intakeBlock, level, cards, userMessages, null);
    }

    // onDecisionStart: 결정 호출로 넘어가는 순간의 훅 — 진행 화면이 단계를 갱신하는 데 쓴다.
    public CompletableFuture<ReadingDraft> read(Assessment saved, ReunionDiagnosis diagnosis,
                                                String intakeBlock, String level,
                                                List<DiagnosisCard> cards,
                                                List<String> userMessages,
                                                Runnable onDecisionStart) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(readingProperties.fullGuide()));
        // packet은 user 턴으로 보낸다 — system만 보내면 전부 systemInstruction으로 빠져
        // contents가 비고, Gemini가 400(contents field is required)으로 거절한다(실측).
        // coda(판독 직전 명령)는 packet 뒤에 붙는다 — 모델이 읽는 마지막 문장이라 위치상
        // 가장 강하다(guide 중간의 같은 규칙은 사연에 밀려 지는 게 실측됐다).
        // 한 번 벌크 수정에 조립부가 덮여 통째로 빠진 사고가 있었다 — packet_appendsCoda 테스트가 지킨다.
        String coda = readingProperties.effectiveCoda();
        String tail = (coda == null || coda.isBlank()) ? "" : "\n\n" + coda;
        boolean baseline = readingProperties.isBaseline();
        // 유저 원문은 packet(JSON)이 아니라 그 뒤에 글 모양 그대로 놓는다. JSON 문자열로
        // 이스케이프돼 11개 키 중 하나로 묻히면 규칙 더미에 눌려 안 읽히는 게 실측됐다
        // (같은 원문을 글로 받은 외부 모델은 인용하며 걸어갔고, 우리 판독은 요약어로 떴다).
        // 위치는 coda 바로 앞 — 모델이 마지막으로 읽는 덩이가 "사연 원문 + 최후 명령"이 된다.
        prompt.add(ChatMessage.user(PAYLOAD_HEADER + "\n"
                + (baseline
                        ? baselinePacketJson(saved, diagnosis)
                        : packetJson(saved, diagnosis, intakeBlock, level, cards)
                                + axisBlock(saved, diagnosis))
                + "\n\n[대화 원문 — 시간순. (사연자)가 유저가 실제로 쓴 말 그대로고, (상담자)는 우리 쪽이"
                + " 물은 것이다. 정황과 표현의 원천은 (사연자) 발화이고, 인용은 거기서 원문 그대로]\n"
                + String.join("\n\n", userMessages == null ? List.of() : userMessages)
                + tail));
        if (baseline) {
            dumpPacketQuietly(prompt);
        }
        // baseline은 무엇이 나오는지 보는 자리라 어휘, 질문 꼴 린트를 걸지 않는다 —
        // 재생성으로 다듬으면 모델이 원래 내는 모양을 못 본다.
        // 판독과 리포트는 한 호출이다 — 편집자를 갈랐을 때 통찰이 유실되고 분산이 늘었다(실측).
        return call(prompt, baseline)
                .thenCompose(json -> baseline
                        ? CompletableFuture.completedFuture(json)
                        : lintRetry(json, prompt))
                .thenApply(json -> baseline
                        ? parseBaseline(json, diagnosis, cards)
                        : parse(json, diagnosis, cards));
    }

    // ── 2단 파이프라인 (본선, 2026-08-26 전환) ─────────────────────────
    // 1단: 자유 분석 — 지시를 최소로 둔다(시스템 한 줄 + 요청 한 줄). 가이드와 구조화
    // 출력이 통찰을 누른다는 실측으로 내린 결정. 원석의 깊이는 여기서 나온다.
    // 2단: 배분 편집 — 싼 모델이 원석을 장(분석, 마음, 답, 행동)으로 무손실 배분하고
    // 등급(앵커는 reading.yml), 판정 한 줄, 카드, 칩, 게이트를 확정한다.

    public record DirectReading(String caseStatus, String gateNote, ReadingDraft draft) {
    }

    public static final String ANALYSIS_SYSTEM = "너는 이별과 재회를 분석하는 전문가다.";
    // 등급이나 확률을 직접 묻지 않는다 — "어느 정도인지"를 물으면 요소 나열과 퍼센트로
    // 답하는 점수판 관성이 나온다(실측). 등급은 2단이 앵커로 정한다.
    // 서비스와 장 구성 설명은 reading.yml의 analysis-guide(시스템 지시)가 맡는다.
    // "사연자가 스스로 보지 못하고 있는 것까지"를 붙였더니 그 문구가 "사연자님이 놓치고 있는 핵심"
    // 총평 대목으로 메아리침(실측 2026-09-08). 무엇을 보는 글인지는 헌법의 페르소나가 말한다.
    // "분석해라"는 sol에게 분석가 등록을 켠다 — 사례를 전형과 대조해 무엇이 아닌지를 가리는 글
    // (열 판 내내 "A라기보다 B" 검정). 등록을 바꾸는 실험(2026-09-09) — 짧게, 절이 소제목으로 메아리치지 않게.
    public static final String ANALYSIS_ASK = "그 사람 쪽에서 무슨 일이 있었는지 써라.";
    // 현재화 호출 식별용 — mock이 이 문구로 분기한다.
    public static final String PRESENT_ASK = "이 관계가 지금 어떤 상태로 남았는지 재구성해라.";
    // 판정 호출 식별용 — mock이 이 문구로 분기한다.
    public static final String VERDICT_ASK = "이 사연의 재회 가능성을 판단해라.";
    // 등급 매퍼 호출 식별용 — mock이 이 문구로 분기한다.
    public static final String GRADE_ASK = "다음 판독문의 재회 가능성 강도를 등급으로 옮겨라.";

    // 종합 호출 식별용 — mock이 이 문구로 분기한다.
    public static final String SYNTH_ASK = "다음 독립 판독들을 비교해 최종 판독을 작성해라.";

    // 품질 심사 호출 식별용 — mock이 이 문구로 분기한다.
    public static final String CRITIC_ASK = "다음 재회 판독문의 품질을 심사해라.";
    // 2단(편집) 호출 식별용 — mock이 이 문구로 분기한다.
    public static final String EDIT_HEADER = "너는 판독 리포트의 편집자다";
    // 카드 분류 호출 식별용 — mock이 이 문구로 분기한다.
    public static final String CARD_ASK = "다음 분석에서 재회 가능성을 가른 문단에 라벨을 붙여라.";
    // 상대 마음 호출 식별용 — mock이 이 문구로 분기한다.
    public static final String MIND_ASK = "이 사람이 지금 무슨 마음이고 무슨 생각을 하는지 분석해라.";

    public static final String EDIT_ASK = "이 글을 다듬어라.";

    public CompletableFuture<DirectReading> readDirect(String intakeBlock,
                                                       String todayLine,
                                                       List<String> userMessages) {
        String story = storyBlock(intakeBlock, todayLine, userMessages);
        List<ChatMessage> first = new ArrayList<>();
        String analysisSystem = readingProperties.getAnalysisGuide() != null
                && !readingProperties.getAnalysisGuide().isBlank()
                ? readingProperties.getAnalysisGuide() : ANALYSIS_SYSTEM;
        // 참고 지식은 헌법 뒤에 딸린 자료로 — 한 덩어리로 두면 지식 문장이 지시로 읽힌다.
        String knowledge = loadKnowledge();
        if (knowledge != null && !knowledge.isBlank()) {
            analysisSystem = analysisSystem + "\n\n[재회 판단의 기준]\n" + knowledge;
        }
        // 등급 잣대는 sol에게도 — sol이 [가능성 등급]을 고르는 판(2026-09-11)에서 잣대 없이 고르면
        // 같은 사연이 판마다 MID와 HIGH를 오갔다. 잣대는 우리가 정하고 고르는 것은 모델이 한다.
        // 다섯 등급의 정의만 준다 — 잣대 머리말(무접촉 해석, 근거 없음의 취급)은 분류 호출용 목줄이라
        // sol에게 가면 본문이 그 문장을 읊고 판단을 피한다(2026-09-11 실측: 593 변호 문단, 5년 MID).
        String gradeGuide = gradeDefinitions(readingProperties.getGradeGuide());
        if (gradeGuide != null && !gradeGuide.isBlank()) {
            analysisSystem = analysisSystem + "\n\n[등급 잣대]\n" + gradeGuide;
        }
        first.add(ChatMessage.system(analysisSystem));
        String ask = readingProperties.getAnalysisAsk();
        // 물음이 비어 있으면 사연만 준다 — "그 사람 쪽에서 무슨 일이 있었는지 써라"가 결론 앞에 그 사람 쪽 요약
        // 대목을 만드는 메아리(2026-09-14, 15 실측)라 헌법이 목적을 다 말하는 지금은 물음이 없어도 된다.
        first.add(ChatMessage.user(ask == null || ask.isBlank() ? story : ask + "\n\n" + story));
        dumpPacketQuietly(first);
        // 판정 호출은 분석 뒤 순차 — 판정이 분석 원석을 재료로 받는다(2026-08-29 전환).
        // 병렬이던 시절엔 가장 자유로운 호출(분석)과 가장 중요한 장(판정)이 서로 못 봐서
        // 판정이 사연 표면만 읽었다. 대기 시간이 한 호출만큼 늘어나는 것이 대가다.
        // 등급은 분석이 모른다 — 분석문만 읽는 매퍼가 한 줄을 붙인다(2026-09-08). 등급을 같은
        // 호출에 두면 한 줄이어도 그 근거를 글이 떠안아 여는 문단과 마지막 대목이 판정문이 된다.
        // 판정 호출이 켜져 있으면(구 분리 구조) 등급은 그 경로가 붙이므로 여기선 건너뛴다.
        boolean verdictOn = readingProperties.getVerdictGuide() != null
                && !readingProperties.getVerdictGuide().isBlank();
        // 등급은 분류 호출이 라벨과 책갈피와 함께 낸다(2026-09-09, 호출 하나 줄임) — 분류는 JSON이라
        // 등급 토큰이 서사를 끌어당길 자리가 없다. 분류가 등급을 못 냈을 때만 매퍼가 뒤에서 붙인다.
        return analysisCall(first)
                // 편집(luna)과 분류(terra)는 sol 원문에서 동시에 돈다 — 편집은 문단 수와 소제목을 못
                // 바꾸므로 분류가 원문에 매긴 문단 번호가 편집본에도 그대로 맞는다. 조립 때 편집본이
                // 꼴 검사를 통과하면 그 문단들 위에 라벨을 얹고, 아니면 원문 위에 얹는다(2026-09-10).
                // 상대 마음 호출도 병렬 — 셋 다 분석만 읽고 서로를 모른다.
                .thenCompose(a0 -> {
                    // sol이 본문 뒤에 쓴 [올리는 것]/[내리는 것] 목록은 갈라 둔다 — 편집과 분류는
                    // 본문만 보고, 목록은 조립 때 정리표에 실린다(2026-09-10, 같은 호출 목록).
                    dumpSolRawQuietly(a0);
                    SolList sl = splitSolList(a0);
                    if (!sl.present() || sl.up().isEmpty()) {
                        log.warn("sol 목록이 비어 있음 — logs/last-sol-raw.txt로 표식을 확인");
                    }
                    if (sl.grade() == null && a0 != null && a0.contains(GRADE_MARK)) {
                        log.warn("sol 등급을 못 읽음(중간을 골랐거나 다른 말) — 폴백 MID로 조립됨, 원문 확인");
                    }
                    // sol이 고른 등급은 "판정 등급:" 줄로 본문 뒤에 붙는다 — 분류 호출은 등급 줄이 있으면
                    // 잣대로 다시 매기지 않고 그 등급의 이유만 쓴다(2026-09-11, 결론 낸 쪽이 등급도 고른다).
                    String a = sl.grade() == null ? sl.body()
                            : sl.body() + "\n\n판정 등급: " + sl.grade();
                    if (sl.present() || sl.grade() != null) {
                        log.info("sol 목록: 올림 {} 내림 {} 유형 {} 등급 {}", sl.up().size(), sl.down().size(),
                                sl.types().size(), sl.grade() == null ? "없음(분류 호출이 매김)" : sl.grade());
                    }
                    CompletableFuture<String> edited = editQuietly(a);
                    return cardQuietly(a, story, edited, sl)
                            .thenCombine(mindQuietly(a, story), (carded, mind) ->
                                    List.of(carded, mind == null ? "" : mind));
                })
                .thenCompose(pair -> verdictOn ? CompletableFuture.completedFuture(pair)
                        : gradeAnalysisQuietly(pair.get(0)).thenApply(g -> List.of(g, pair.get(1))))
                .thenCompose(pair -> {
            String analysis = pair.get(0);
            String mind = pair.get(1);
            // 현재화 호출 — 과거의 감정이 지금 무엇으로 남았는지만 재구성. 판정이 이
            // 단계를 건너뛰지 못하게 독립 산출물로 만든다(호출당 인지 작업 하나 원칙).
            String presentGuide = readingProperties.getPresentGuide();
            CompletableFuture<String> presentFuture;
            if (presentGuide != null && !presentGuide.isBlank()) {
                List<ChatMessage> presentPrompt = new ArrayList<>();
                presentPrompt.add(ChatMessage.system(presentGuide));
                presentPrompt.add(ChatMessage.user(PRESENT_ASK + "\n\n" + story
                        + "\n\n[전문가의 분석]\n" + analysis));
                presentFuture = presentCall(presentPrompt);
            } else {
                presentFuture = CompletableFuture.completedFuture("");
            }
            return presentFuture.thenCompose(present -> {
                String verdictGuide = readingProperties.getVerdictGuide();
                CompletableFuture<String> verdictFuture;
                // 종합 시 가지 원석 보관용 — 종합이 통찰을 죽였는지 가지부터 얕았는지
                // 가르는 유일한 자료라 synthesis 컬럼에 함께 남긴다.
                StringBuilder branchLog = new StringBuilder();
                if (verdictGuide != null && !verdictGuide.isBlank()) {
                    // 현재화가 있으면 판정자에게 원사연을 주지 않는다 — 강한 표면
                    // 토큰("다신 연락하지 마", "4년")에 다시 끌려가는 것을 막는다.
                    String verdictInput = present.isBlank()
                            ? VERDICT_ASK + "\n\n" + story + "\n\n[전문가의 분석]\n" + analysis
                            : VERDICT_ASK + "\n\n[현재 상태 판독]\n" + present;
                    int samples = readingProperties.getVerdictSamples();
                    String synthesisGuide = readingProperties.getVerdictSynthesisGuide();
                    boolean parallel = samples > 1
                            && synthesisGuide != null && !synthesisGuide.isBlank();
                    if (!parallel) {
                        verdictFuture = verdictCall(verdictPrompt(verdictGuide, verdictInput));
                    } else {
                        // 병렬 독립 판독 — 단일 샘플은 안전한 해석으로 수렴한다. 가지들은
                        // 서로를 못 보고, 종합이 비교해 최종 판독을 새로 쓴다. 기각된
                        // 5단(직렬 압축)과 달리 각 가지가 원문 전체를 받는다.
                        List<CompletableFuture<String>> branches = new ArrayList<>();
                        for (int i = 0; i < samples; i++) {
                            branches.add(verdictCall(verdictPrompt(verdictGuide, verdictInput)));
                        }
                        verdictFuture = CompletableFuture
                                .allOf(branches.toArray(new CompletableFuture[0]))
                                .thenCompose(v -> {
                                    StringBuilder sy = new StringBuilder(SYNTH_ASK)
                                            .append("\n\n").append(story)
                                            .append("\n\n[전문가의 분석]\n").append(analysis);
                                    for (int i = 0; i < branches.size(); i++) {
                                        String branch = branches.get(i).join();
                                        sy.append("\n\n[판독 ").append(i + 1).append("]\n")
                                                .append(branch);
                                        branchLog.append("\n\n[판독 가지 ").append(i + 1)
                                                .append("]\n").append(branch);
                                    }
                                    List<ChatMessage> synthPrompt = new ArrayList<>();
                                    synthPrompt.add(ChatMessage.system(synthesisGuide));
                                    synthPrompt.add(ChatMessage.user(sy.toString()));
                                    return synthesisCall(synthPrompt);
                                });
                    }
                } else {
                    verdictFuture = CompletableFuture.completedFuture("");
                }
                StringBuilder criticLog = new StringBuilder();
                CompletableFuture<String> vettedFuture = verdictFuture
                        .thenCompose(v -> critiqueQuietly(v, story, analysis, criticLog));
                return vettedFuture.thenCompose(this::gradeQuietly).thenCompose(verdict -> {
                    List<ChatMessage> second = new ArrayList<>();
                    second.add(ChatMessage.system(readingProperties.fullGuide()));
                    StringBuilder editInput = new StringBuilder("[사연]\n").append(story)
                            .append("\n\n[전문가의 분석]\n").append(analysis);
                    if (!present.isBlank()) {
                        editInput.append("\n\n[현재 상태 판독]\n").append(present);
                    }
                    if (!verdict.isBlank()) {
                        editInput.append("\n\n[재회 가능성 판정]\n").append(verdict);
                    }
                    second.add(ChatMessage.user(editInput.toString()));
                    // 원석 보관 — 재편집과 골든셋 채점의 원본.
                    StringBuilder raw = new StringBuilder(analysis);
                    if (!present.isBlank()) {
                        raw.append("\n\n[현재 상태 판독]\n").append(present);
                    }
                    if (branchLog.length() > 0) {
                        raw.append(branchLog);
                    }
                    if (criticLog.length() > 0) {
                        raw.append(criticLog);
                    }
                    if (!verdict.isBlank()) {
                        raw.append("\n\n[재회 가능성 판정]\n").append(verdict);
                    }
                    // 원석 맨 끝 — analysisSection이 [재회 가능성]과 등급 줄에서 끊으므로 화면
                    // 문단 파서에는 안 잡히고, 편집 입력에도 안 실린다(보관과 SQL 확인용).
                    if (!mind.isBlank()) {
                        raw.append("\n\n[그 사람의 지금 마음과 생각]\n").append(mind);
                    }
                    if (!readingProperties.isDecisionCall()) {
                        return CompletableFuture.completedFuture(fromRaw(raw.toString()));
                    }
                    return editCall(second).thenApply(json -> parseEdited(json, raw.toString()));
                });
            });
        });
    }

    // 1단에 주는 사연 묶음 — 전부 글 모양 그대로(JSON으로 싸면 규칙 더미에 눌려 안 읽히는 게 실측).
    // 오늘 날짜가 없으면 사연 속 상대 시간 표현이 전부 현재로 읽힌다(실측 619, 7개월 미인지).
    // 사연자가 적던 물음([사연자가 물은 것])은 2026-09-16에 기능째 뺐다 — 물음이 곧 "다시 만날 수
    // 있을까요"라 헌법 첫 줄과 겹치고, 본문이 그 물음에 답하는 꼴로 판정을 끌어들였다.
    private String storyBlock(String intakeBlock, String todayLine, List<String> userMessages) {
        StringBuilder sb = new StringBuilder();
        if (todayLine != null && !todayLine.isBlank()) {
            sb.append(readingTodayLine(todayLine)).append('\n');
        }
        if (intakeBlock != null && !intakeBlock.isBlank()) {
            sb.append("[문진으로 확인된 것]\n").append(intakeBlock).append("\n\n");
        }
        sb.append("[사연 — 대화 원문 그대로, 시간순. (사연자)가 유저가 실제로 쓴 말이고 (상담자)는"
                + " 우리 쪽이 물은 것이다. 사실과 인용의 원천은 (사연자) 발화이고, (상담자) 발화는 그 답이"
                + " 무엇에 대한 것인지를 읽는 데만 쓴다. 각 발화 앞 (월/일)은 그 말을 한 날짜고,"
                + " 발화 속 시간 표현은 그 날짜 기준이다]\n");
        sb.append(String.join("\n\n", userMessages == null ? List.of() : userMessages));
        return sb.toString();
    }

    // 개발용 재분류 — 저장된 원석에서 분석 원문(카드 구획 앞)과 등급 줄만 꺼내 분류 호출을 다시
    // 돌린다. 분석은 다시 사지 않는다. 결과는 새 원석 텍스트(라벨, 이름, 정리표 포함).
    public CompletableFuture<String> reclassify(String rawAnalysis, String intakeBlock,
                                                String todayLine, List<String> userMessages) {
        if (rawAnalysis == null || rawAnalysis.isBlank()) {
            return CompletableFuture.completedFuture("");
        }
        int cardsAt = rawAnalysis.indexOf("[재회 가능성]");
        String head = (cardsAt >= 0 ? rawAnalysis.substring(0, cardsAt) : rawAnalysis).strip();
        int gradeAt = rawAnalysis.lastIndexOf("판정 등급");
        String tail = gradeAt >= 0 ? rawAnalysis.substring(gradeAt).strip() : "";
        // 구 원석은 분석 구획 안에 등급 줄이 남아 있을 수 있다 — 한 번만 붙게 걷어낸다.
        int headGrade = head.indexOf("판정 등급");
        if (headGrade >= 0) {
            head = head.substring(0, headGrade).strip();
        }
        String analysis = tail.isBlank() ? head : head + "\n\n" + tail;
        String story = storyBlock(intakeBlock, todayLine, userMessages);
        return cardQuietly(analysis, story, CompletableFuture.completedFuture(null), SolList.EMPTY);
    }

    // 1호출용 todayLine("… 시간 계산은 이 날짜 기준이다")을 그대로 실으면 분석이 날수를 세어
    // 본문에 쓴다(실측: "23일이 지났고 2주는 9일 넘었다" 문단 4판 연속). 분석에는 날짜만 주고
    // 용도를 사연 속 시간 표현의 기준으로 못 박는다.
    // sol 원문을 파일로 남긴다 — 분류 호출이 꺼진 뒤로는 패킷 덤프가 없어 표식 파싱을 대조할 수단이 이것뿐.
    private void dumpSolRawQuietly(String raw) {
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("logs", "last-sol-raw.txt"), raw == null ? "" : raw);
        } catch (Exception e) {
            log.debug("sol 원문 덤프 실패(무시)");
        }
    }

    // knowledge-file이 있으면 그 파일 전문, 없으면 reading.yml knowledge. 파일을 못 읽으면 경고 후 knowledge.
    private String loadKnowledge() {
        String file = readingProperties.getKnowledgeFile();
        if (file != null && !file.isBlank()) {
            try {
                String text = java.nio.file.Files.readString(java.nio.file.Path.of(file));
                log.info("지식 파일 사용: {} ({}자)", file, text.length());
                return text;
            } catch (Exception e) {
                log.warn("지식 파일을 읽지 못해 knowledge로 대신: {} — {}", file, e.toString());
            }
        }
        return readingProperties.getKnowledge();
    }

    // grade-guide에서 "VERY_LOW:"부터의 정의 부분만. 정의 줄이 없으면 전체.
    static String gradeDefinitions(String gradeGuide) {
        if (gradeGuide == null) {
            return null;
        }
        int at = gradeGuide.indexOf("VERY_LOW:");
        return at < 0 ? gradeGuide : gradeGuide.substring(at).strip();
    }

    static String readingTodayLine(String todayLine) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\d{4}-\\d{2}-\\d{2}").matcher(todayLine);
        if (!m.find()) {
            return todayLine;
        }
        return "오늘 날짜: " + m.group() + " — 사연 속 날짜와 상대 시간 표현을 읽는 기준일이다.";
    }

    private CompletableFuture<String> analysisCall(List<ChatMessage> prompt) {
        CompletableFuture<String> out = "openai".equalsIgnoreCase(readingProperties.getProvider())
                ? openAiClient.generateReadingText(prompt, "분석", readingProperties.getAnalysisModel())
                // 그 외 프로바이더(mock 포함)는 기존 통로로 — mock이 프롬프트 내용으로 분기한다.
                : llmClient.generateJsonDeep(prompt, null);
        // 모델이 스스로 "[전문가의 분석]" 머리말을 쓰는지 본다 — 원석은 서버가 머리말을 붙여 조립하므로
        // 원석만으로는 알 수 없다(2026-09-10, 장르 신호 실험).
        return out.thenApply(a -> {
            if (a == null) {
                return null;
            }
            // 소제목 줄을 빼자 강조를 **볼드**로 하기 시작했다(2026-09-10) — 화면은 마크다운을 안 그리므로
            // 별표만 걷는다. 지시로 막는 것보다 확실하고, 뜻은 그대로다.
            String cleaned = a.replace("**", "");
            log.debug("분석 첫 줄: {}", cleaned.strip().lines().findFirst().orElse(""));
            return cleaned;
        });
    }

    // 편집 호출 — 본문의 문장 꼴만 다듬는다. sol의 읽기는 두고 앞절 없는 부정문, 주어 반복, 규칙
    // 검산 문장을 걷는다. 헌법으로 열두 판을 시험해도 "X가 아니라 Y"가 4~11 사이를 오간 습관의
    // 프롬프트 밖 처방(2026-09-10). 문단 수나 소제목이 달라지면 편집을 버리고 원문을 쓴다 —
    // 분류 호출이 문단 번호로 라벨을 붙이므로 꼴이 어긋나면 카드가 엉뚱한 문단을 가리킨다.
    private CompletableFuture<String> editQuietly(String analysis) {
        String guide = readingProperties.getEditGuide();
        if (guide == null || guide.isBlank() || analysis == null || analysis.isBlank()
                || !"openai".equalsIgnoreCase(readingProperties.getProvider())) {
            // 편집을 건너뛴 판은 null — 원문을 돌려주면 "편집 반영"으로 잘못 찍힌다.
            return CompletableFuture.completedFuture(null);
        }
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(guide));
        prompt.add(ChatMessage.user(EDIT_ASK + "\n\n[본문]\n" + analysis));
        return openAiClient.generateReadingText(prompt, "편집", readingProperties.getEditModel())
                .thenApply(edited -> edited == null ? null : stripEditEcho(edited))
                .exceptionally(e -> {
                    log.warn("편집 실패 — 원문 유지: {}", e.toString());
                    return null;
                });
    }

    // luna가 입력의 머리말("[사연자가 물은 것] …", "[본문]")을 출력에 따라 쓴다(실측 2026-09-10: 문단이
    // 8 → 9가 되어 편집이 통째로 버려짐). 라벨 뒤의 본문만 남긴다.
    static String stripEditEcho(String edited) {
        String out = edited.replace("**", "");
        int body = out.lastIndexOf("[본문]");
        if (body >= 0) {
            out = out.substring(body + "[본문]".length());
        }
        return out.strip();
    }

    // 편집본을 쓸지 정한다 — 꼴이 같으면 편집본, 아니면 원문. 버려질 때는 둘을 파일로 남겨 대조한다.
    private String pickEdited(String analysis, String edited) {
        if (edited == null || edited.isBlank()) {
            return analysis;
        }
        if (editKeepsShape(analysis, edited)) {
            log.info("편집 반영: {}자 → {}자", analysis.length(), edited.length());
            return edited;
        }
        List<Para> a = splitHeaded(analysisSection(analysis));
        List<Para> b = splitHeaded(analysisSection(edited));
        log.warn("편집 버림 — 문단 {} → {}, 소제목 {} → {} (원문 {}자, 편집 {}자)",
                a.size(), b.size(),
                a.stream().filter(x -> x.heading() != null).count(),
                b.stream().filter(x -> x.heading() != null).count(),
                analysis.length(), edited.length());
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("logs", "last-edit-original.txt"), analysis);
            java.nio.file.Files.writeString(java.nio.file.Path.of("logs", "last-edit-edited.txt"), edited);
        } catch (Exception e) {
            log.debug("편집 대조 덤프 실패(무시)");
        }
        return analysis;
    }

    // 편집본이 원문과 같은 꼴인지 — 문단 수와 소제목 목록이 같아야 분류의 문단 번호가 맞는다.
    static boolean editKeepsShape(String original, String edited) {
        if (edited == null || edited.isBlank()) {
            return false;
        }
        List<Para> a = splitHeaded(analysisSection(original));
        List<Para> b = splitHeaded(analysisSection(edited));
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            String ha = a.get(i).heading() == null ? "" : a.get(i).heading().strip();
            String hb = b.get(i).heading() == null ? "" : b.get(i).heading().strip();
            if (!ha.equals(hb)) {
                return false;
            }
        }
        return true;
    }

    // 카드 분류 호출(2호출) — 분석은 카드를 모른 채 자유롭게 쓰고(1호출), 이 호출이 문단
    // 번호로 라벨과 제목만 붙인다. 한 호출에 쓰기와 분류를 같이 시키면 분석이 카드 재료
    // 생산으로 기운다는 실측(601: 정점 대비 30% 감량)의 해법. 결과는 [재회 가능성] 구획
    // 텍스트로 렌더해 맺음 문단 앞에 끼워 넣으므로 이후 파서, 편집, 화면은 그대로 쓴다.
    // 실패하면 분석을 그대로 돌려보낸다(편집자 폴백).
    private CompletableFuture<String> cardQuietly(String analysis, String story,
                                                  CompletableFuture<String> editedFuture,
                                                  SolList solList) {
        String guide = readingProperties.getCardGuide();
        if (analysis == null || analysis.isBlank() || guide == null || guide.isBlank()) {
            return editedFuture.thenApply(edited -> withSolList(pickEdited(analysis, edited), solList));
        }
        int gradeAt = analysis.indexOf("판정 등급");
        String head = gradeAt >= 0 ? analysis.substring(0, gradeAt) : analysis;
        String tail = gradeAt >= 0 ? analysis.substring(gradeAt).strip() : "";
        List<Para> split = splitHeaded(head);
        List<String> paras = split.stream().map(Para::body).toList();
        List<String> headings = split.stream().map(Para::heading).toList();
        // sol이 (핵심)을 붙인 판에서는 핵심의 확정자가 sol이다 — 등급을 고른 쪽이 무엇이
        // 판을 만들었는지 안다. 안 붙인 판만 luna의 pivot을 쓴다.
        List<Boolean> pivots = split.stream().anyMatch(Para::pivot)
                ? split.stream().map(Para::pivot).toList() : null;
        // (높낮이를 가를 수 있는 것) 대목은 확인되면 판이 달라지는 자리라 luna가 무슨 방향을
        // 찍어도 미정이다 — 결론을 내지 않는 대목에 방향을 박으면 그 자리가 사라진다.
        List<Boolean> hinges = split.stream().map(Para::hinge).toList();
        if (paras.size() < 2) {
            return editedFuture.thenApply(edited -> withSolList(pickEdited(analysis, edited), solList));
        }
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < paras.size(); i++) {
            numbered.append('[').append(i + 1).append("] ");
            if (headings.get(i) != null) {
                numbered.append("소제목: ").append(headings.get(i)).append('\n');
            }
            numbered.append(paras.get(i)).append("\n\n");
        }
        List<ChatMessage> prompt = new ArrayList<>();
        // 등급 줄이 아직 없으면 이 호출이 등급도 고른다 — 잣대(grade-guide)를 지시 뒤에 붙인다.
        String gradeGuide = readingProperties.getGradeGuide();
        String system = tail.isBlank() && gradeGuide != null && !gradeGuide.isBlank()
                ? guide + "\n\n[등급 잣대]\n" + gradeGuide : guide;
        prompt.add(ChatMessage.system(system));
        // 등급 줄이 있으면 정리표의 "지금 이 등급인 이유"를 쓰라고 준다 — 문단 번호 밖이라 카드는 못 된다.
        prompt.add(ChatMessage.user(CARD_ASK + "\n\n" + story
                + "\n\n[전문가의 분석 — 문단 번호]\n" + numbered
                + (tail.isBlank() ? "" : "\n" + tail)));
        dumpPacketQuietly(prompt);
        CompletableFuture<String> jsonFuture = cardCall(prompt)
                .exceptionally(e -> {
                    log.warn("카드 분류 호출 실패 — 편집자 폴백: {}", e.toString());
                    return null;
                });
        return jsonFuture.thenCombine(editedFuture.exceptionally(e -> null), (json, edited) -> {
            // 편집본이 꼴을 지켰으면 그 문단들 위에 라벨을 얹는다 — 번호는 원문과 같다.
            String body = pickEdited(analysis, edited);
            List<Para> bodySplit = body == analysis ? split
                    : splitHeaded(body.indexOf("판정 등급") >= 0
                            ? body.substring(0, body.indexOf("판정 등급")) : body);
            List<String> bodyParas = bodySplit.stream().map(Para::body).toList();
            List<String> bodyHeadings = bodySplit.stream().map(Para::heading).toList();
            if (json == null) {
                return withSolList(body, solList);
            }
            try {
                String composed = composeWithCards(json, bodyParas, bodyHeadings, pivots, hinges, tail,
                        solList);
                if (composed == null) {
                    log.warn("카드 분류 호출이 카드를 내지 않음 — 편집자 폴백");
                    return withSolList(body, solList);
                }
                return composed;
            } catch (Exception e) {
                log.warn("카드 분류 응답 파싱 실패 — 편집자 폴백: {}", e.toString());
                return withSolList(body, solList);
            }
        });
    }

    // 분류는 결정 모델(luna)이 한다 — 같은 원석으로 A/B한 결과(2026-09-06, 631): luna는 골라야
    // 할 사건 문단을 제대로 골랐고 핵심도 절제했으나 과잉 승격과 정리 압축이 결함, sol은 요약
    // 문단만 집고 핵심을 남발했다. 전자는 지시와 서버 가드(정리 문장 검증)로 막히는 종류라
    // 싼 모델로 간다.
    private CompletableFuture<String> cardCall(List<ChatMessage> prompt) {
        if ("openai".equalsIgnoreCase(readingProperties.getProvider())) {
            // card-by-sol이면 판독 모델로 — 같은 스키마, 같은 지시. 로그의 kind로 구분된다.
            return readingProperties.isCardBySol()
                    ? openAiClient.generateReadingJson(prompt, CARD_SCHEMA, "카드분류",
                            readingProperties.getCardModel())
                    : openAiClient.generateDecisionJson(prompt, CARD_SCHEMA, "카드분류");
        }
        return llmClient.generateJsonDeep(prompt, CARD_SCHEMA);
    }

    // 분류 응답을 [재회 가능성] 구획으로 렌더해 분석에 끼운다. 라벨 카드의 본문은 번호가
    // 가리키는 원문 문단 그대로, 정리(reworked) 카드는 응답의 본문을 쓰고 원래 문단에는
    // (아래에서 정리)를 달아 02 산문에서 빠지게 한다. 마지막 문단(맺음)은 카드가 될 수 없다.
    // 분석 문단 하나 — sol이 "## 소제목" 줄을 앞세운 문단은 소제목을 따로 든다(없으면 null).
    // pivot은 그 문단이 속한 대목의 소제목에 sol이 (핵심)을 붙였는지, hinge는 (높낮이를 가를
    // 수 있는 것)을 붙였는지 — 확인되면 판이 달라지는 대목. 둘 다 대목 단위라 소제목이 없는
    // 뒤 문단에도 다음 소제목 전까지 이어진다.
    record Para(String heading, String body, boolean pivot, boolean hinge) {
    }

    // 소제목 끝 괄호 표시 — (핵심), (높낮이를 가를 수 있는 것), 둘이 같이 오면 쉼표로.
    private static final java.util.regex.Pattern HEADING_PIVOT =
            java.util.regex.Pattern.compile("\\s*[(\\[]\\s*핵심\\s*[)\\]]\\s*$");
    private static final java.util.regex.Pattern HEADING_HINGE = java.util.regex.Pattern.compile(
            "\\s*[(\\[]\\s*(?:높\\s*낮이|판)\\s*를?\\s*가를\\s*수\\s*있는\\s*것\\s*[)\\]]\\s*$");
    private static final java.util.regex.Pattern HEADING_BOTH = java.util.regex.Pattern.compile(
            "\\s*[(\\[]\\s*(?:핵심|(?:높\\s*낮이|판)\\s*를?\\s*가를\\s*수\\s*있는\\s*것)\\s*,\\s*"
                    + "(?:핵심|(?:높\\s*낮이|판)\\s*를?\\s*가를\\s*수\\s*있는\\s*것)\\s*[)\\]]\\s*$");

    // 빈 줄로 문단을 나누되 "## " 줄은 소제목으로 뗀다. 소제목만 있는 덩이는 다음 문단의
    // 소제목이 된다. 첫 덩이의 [전문가의 분석] 머리는 걷어낸다.
    static List<Para> splitHeaded(String text) {
        List<Para> out = new ArrayList<>();
        String pending = null;
        boolean sectionPivot = false;
        boolean sectionHinge = false;
        for (String chunk : text.split("\\n\\s*\\n")) {
            String s = chunk.strip();
            if (s.startsWith("[전문가의 분석]")) {
                s = s.substring("[전문가의 분석]".length()).strip();
            }
            if (s.isEmpty()) {
                continue;
            }
            String heading = pending;
            pending = null;
            String[] lines = s.split("\\n", 2);
            if (lines[0].strip().startsWith("#")) {
                heading = lines[0].strip().replaceFirst("^#+\\s*", "").replaceAll("\\.$", "").strip();
                // 소제목 끝 표시는 sol이 대목에 붙인 것 — 소제목에서 떼고 대목 표시로 든다.
                sectionPivot = false;
                sectionHinge = false;
                java.util.regex.Matcher bm = HEADING_BOTH.matcher(heading);
                if (bm.find()) {
                    sectionPivot = bm.group().contains("핵심");
                    sectionHinge = bm.group().contains("가를");
                    heading = bm.replaceFirst("").strip();
                } else {
                    java.util.regex.Matcher pm = HEADING_PIVOT.matcher(heading);
                    if (pm.find()) {
                        sectionPivot = true;
                        heading = pm.replaceFirst("").strip();
                    }
                    java.util.regex.Matcher hm = HEADING_HINGE.matcher(heading);
                    if (hm.find()) {
                        sectionHinge = true;
                        heading = hm.replaceFirst("").strip();
                    }
                }
                s = lines.length > 1 ? lines[1].strip() : "";
                if (s.isEmpty()) {
                    pending = heading;
                    continue;
                }
            }
            out.add(new Para(heading == null || heading.isBlank() ? null : heading, s,
                    sectionPivot, sectionHinge));
        }
        return out;
    }

    private String composeWithCards(String json, List<String> paras, List<String> headings,
                                    List<Boolean> pivots, List<Boolean> hinges, String tail,
                                    SolList solList)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
        // 1번 문단은 리드, 마지막 문단은 맺음 — 둘 다 카드가 될 수 없다.
        int last = paras.size();
        java.util.Map<Integer, String> cardAt = new java.util.TreeMap<>();
        for (JsonNode node : root.path("cards")) {
            int para = node.path("para").asInt(0);
            String title = text(node, "title").strip();
            // 소제목은 대목(여러 문단)의 결론이라 문단 하나짜리 대목에만 카드 제목으로 쓴다.
            if (para >= 1 && para <= headings.size() && headings.get(para - 1) != null
                    && (para == headings.size() || headings.get(para) != null)) {
                title = headings.get(para - 1);
            }
            if (para < 2 || para >= last || title.isBlank()) {
                log.warn("카드 분류 무시 — 번호 {} 제목 '{}'", para, title);
                continue;
            }
            // 본문은 복사하지 않는다 — 번호 참조만. 원석에 같은 문단이 두 번 실리던 것을 걷었다(2026-09-08).
            cardAt.put(para, cardHeader(node, pivotOf(pivots, para), hingeOf(hinges, para))
                    + ' ' + title + "\n[" + para + "]\n\n");
        }
        if (cardAt.isEmpty()) {
            return null;
        }
        log.info("카드 분류: 라벨 {}문단 (분석 {}문단)", cardAt.size(), last);
        StringBuilder out = new StringBuilder("[전문가의 분석]\n\n");
        for (int i = 0; i < last; i++) {
            if (headings.get(i) != null) {
                out.append("## ").append(headings.get(i));
                boolean pv = pivots != null && pivots.get(i);
                boolean hg = hinges.get(i);
                if (pv && hg) {
                    out.append(" (핵심, 높낮이를 가를 수 있는 것)");
                } else if (pv) {
                    out.append(" (핵심)");
                } else if (hg) {
                    out.append(" (높낮이를 가를 수 있는 것)");
                }
                out.append('\n');
            }
            out.append(paras.get(i)).append("\n\n");
        }
        out.append("[재회 가능성]\n(분류 호출의 결과 — 라벨과 제목만. [번호]는 위 분석의 문단 번호)\n\n");
        for (String card : cardAt.values()) {
            out.append(card);
        }
        // 대목 이름 다시 짓기는 뺐다(2026-09-08) — sol도 luna도 줄거리 문장으로 다시 써서 sol이 글을
        // 쓰며 단 소제목보다 나을 게 없었다. [이름] 파서는 옛 원석용으로 남긴다.
        String summary = renderSummary(root.path("summary"), solList);
        if (summary != null) {
            out.append(summary).append("\n\n");
        }
        String grade = tail;
        if (grade.isBlank()) {
            String g = text(root, "grade").strip();
            if (OUTLOOK_SCORES.containsKey(g)) {
                grade = "판정 등급: " + g;
            }
        }
        if (!grade.isBlank()) {
            out.append(grade);
        }
        return out.toString().stripTrailing();
    }

    static final String NAMES_MARK = "[이름]";

    // 대목 이름 — 분류 호출이 소제목이 있는 문단에 붙인 짧은 이름. 원석에는 sol의 소제목을 그대로
    // 두고 [이름] 블록으로 따로 적는다(누가 지은 소제목인지 기록이 남게).
    private static String renderNames(JsonNode array, List<String> headings) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode node : array) {
            int para = node.path("para").asInt(0);
            String name = text(node, "title").strip().replace("·", " ").replaceAll("\\.$", "");
            if (para < 1 || para > headings.size() || headings.get(para - 1) == null || name.isBlank()) {
                continue;
            }
            sb.append(para).append(": ").append(name).append('\n');
        }
        return sb.length() == 0 ? null : NAMES_MARK + "\n" + sb.toString().stripTrailing();
    }

    // 원석의 [이름] 블록 — 문단 번호 → 이름.
    static java.util.Map<Integer, String> parseNames(String rawAnalysis) {
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        if (rawAnalysis == null) {
            return out;
        }
        int at = rawAnalysis.indexOf(NAMES_MARK);
        if (at < 0) {
            return out;
        }
        for (String line : rawAnalysis.substring(at + NAMES_MARK.length()).split("\\n")) {
            String t = line.strip();
            if (t.isEmpty()) {
                if (!out.isEmpty()) {
                    break;
                }
                continue;
            }
            if (t.startsWith("[") || t.startsWith("판정 등급")) {
                break;
            }
            int colon = t.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                out.put(Integer.parseInt(t.substring(0, colon).strip()), t.substring(colon + 1).strip());
            } catch (NumberFormatException ignored) {
                // 이름 줄이 아니면 넘긴다
            }
        }
        return out;
    }

    // 카드 본문이 "[n]" 번호 참조면 분석 구획의 n번 문단 원문으로 채운다 — 화면과 소견서 매칭은
    // 본문 텍스트로 하므로 여기서 한 번 풀어 두면 뒤는 예전 그대로다.
    private static final java.util.regex.Pattern PARA_REF =
            java.util.regex.Pattern.compile("^\\[(\\d+)\\]$");

    static List<ReadingDraft.Decision.VerdictCard> resolveParaRefs(
            List<ReadingDraft.Decision.VerdictCard> cards, String rawAnalysis) {
        if (cards.isEmpty() || rawAnalysis == null) {
            return cards;
        }
        List<Para> paras = splitHeaded(analysisSection(rawAnalysis));
        List<ReadingDraft.Decision.VerdictCard> out = new ArrayList<>();
        for (ReadingDraft.Decision.VerdictCard c : cards) {
            java.util.regex.Matcher m = PARA_REF.matcher(c.body().strip());
            if (!m.matches()) {
                out.add(c);
                continue;
            }
            int n = Integer.parseInt(m.group(1));
            if (n < 1 || n > paras.size()) {
                log.warn("카드 번호 참조 [{}]가 문단 밖 — 카드 버림", n);
                continue;
            }
            out.add(new ReadingDraft.Decision.VerdictCard(
                    c.subtitle(), paras.get(n - 1).body().replace("·", ", "), c.direction(), c.pivot()));
        }
        return out;
    }

    private String renderSummary(JsonNode node, SolList solList) {
        boolean none = node == null || node.isMissingNode() || node.isNull();
        if (none && !solList.present()) {
            return null;
        }
        String name = solList.name() != null ? solList.name().replace("·", ", ")
                : none ? "" : text(node, "name").strip().replace("·", ", ");
        String why = solList.why() != null ? solList.why().replace("·", ", ")
                : none ? "" : text(node, "why").strip().replace("·", ", ");
        // sol이 본문 뒤에 목록을 썼으면 그것이 정리표의 올리는 것/내리는 것이다 — 분류 호출의 책갈피는
        // 그 판에서는 안 쓴다. 이름과 이유는 분류 호출 것.
        List<String> up = solList.present() ? solList.up() : keywordItems(node.path("up"));
        List<String> down = solList.present() ? solList.down() : keywordItems(node.path("down"));
        if (name.isBlank() && why.isBlank() && up.isEmpty() && down.isEmpty()) {
            return null;
        }
        // 항목은 줄마다 "- 이름 [문단] — 한 줄" — 한 줄에 쉼표가 올 수 있어 쉼표 구분을 버렸다.
        StringBuilder sb = new StringBuilder(SUMMARY_MARK).append('\n');
        sb.append("이름: ").append(name).append('\n');
        // 목록이 없는 판(73판, 총평만 받음)에서는 빈 머리줄을 안 찍는다 — 파서는 머리줄 없이도 읽는다.
        if (!up.isEmpty()) {
            sb.append("올리는 것:\n");
            for (String item : up) {
                sb.append("- ").append(item).append('\n');
            }
        }
        if (!down.isEmpty()) {
            sb.append("내리는 것:\n");
            for (String item : down) {
                sb.append("- ").append(item).append('\n');
            }
        }
        if (!solList.types().isEmpty()) {
            sb.append("유형:\n");
            for (String item : solList.types()) {
                sb.append("- ").append(item.replace("·", ", ")).append('\n');
            }
        }
        sb.append("이유: ").append(why);
        return sb.toString();
    }

    // "이름 [문단번호] — 한 줄" 꼴. 번호가 없거나 0이면 번호 생략, 한 줄이 없으면 이름까지만.
    private static List<String> keywordItems(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String keyword = (node.isTextual() ? node.asText() : text(node, "keyword"))
                    .strip().replace("·", " ").replaceAll("\\s+", " ").replaceAll("\\.$", "");
            if (keyword.isBlank() || out.size() >= 5) {
                continue;
            }
            int para = node.path("para").asInt(0);
            String note = text(node, "note").strip().replace("·", ", ").replace("\n", " ");
            StringBuilder sb = new StringBuilder(keyword);
            if (para > 0) {
                sb.append(" [").append(para).append(']');
            }
            if (!note.isBlank()) {
                sb.append(" — ").append(note);
            }
            out.add(sb.toString());
        }
        return out;
    }

    // "이름 [문단번호] — 한 줄" 파서. 번호와 한 줄은 둘 다 선택.
    private static final java.util.regex.Pattern KEYWORD_LINE = java.util.regex.Pattern.compile(
            "^(.*?)\\s*(?:\\[(\\d+)\\])?\\s*(?:—\\s*(.*))?$");

    // 문단 번호(1-based) → 키워드. 같은 문단에 둘이 걸리면 먼저 나온 것.
    static java.util.Map<Integer, String> keywordsByPara(ReadingDraft.Decision.Summary summary) {
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        if (summary == null) {
            return out;
        }
        for (List<ReadingDraft.Decision.Summary.Item> items : List.of(summary.up(), summary.down())) {
            for (ReadingDraft.Decision.Summary.Item item : items) {
                if (item.para() != null && item.para() > 0) {
                    out.putIfAbsent(item.para(), item.keyword());
                }
            }
        }
        return out;
    }

    // 원석의 [재회 정리] 블록을 다시 읽는다 — 없으면 null(옛 저장분, 분류 폴백).
    // 줄 항목("- 이름 [n] — 한 줄")과 옛 쉼표 나열("이름 [n], 이름 [n]") 둘 다 받는다.
    static ReadingDraft.Decision.Summary parseSummary(String rawAnalysis) {
        if (rawAnalysis == null) {
            return null;
        }
        int at = rawAnalysis.indexOf(SUMMARY_MARK);
        if (at < 0) {
            return null;
        }
        String name = "";
        String why = "";
        List<ReadingDraft.Decision.Summary.Item> up = new ArrayList<>();
        List<ReadingDraft.Decision.Summary.Item> down = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<ReadingDraft.Decision.Summary.Item> current = null;
        boolean inTypes = false;
        boolean started = false;
        for (String line : rawAnalysis.substring(at + SUMMARY_MARK.length()).split("\\n")) {
            String s = line.strip();
            if (s.isEmpty()) {
                if (started) {
                    break;
                }
                continue;
            }
            if (s.startsWith("[") || s.startsWith("판정 등급")) {
                break;
            }
            started = true;
            if (s.startsWith("이름:")) {
                name = s.substring(3).strip();
                current = null;
                inTypes = false;
            } else if (s.startsWith("올리는 것:")) {
                current = up;
                inTypes = false;
                splitKeywords(s.substring(6), current);
            } else if (s.startsWith("내리는 것:")) {
                current = down;
                inTypes = false;
                splitKeywords(s.substring(6), current);
            } else if (s.startsWith("유형:")) {
                current = null;
                inTypes = true;
            } else if (s.startsWith("이유:")) {
                why = s.substring(3).strip();
                current = null;
                inTypes = false;
            } else if (s.startsWith("- ") && inTypes) {
                types.add(s.substring(2).strip());
            } else if (s.startsWith("- ") && current != null) {
                ReadingDraft.Decision.Summary.Item item = parseKeyword(s.substring(2));
                if (item != null) {
                    current.add(item);
                }
            }
        }
        if (name.isBlank() && why.isBlank() && up.isEmpty() && down.isEmpty() && types.isEmpty()) {
            return null;
        }
        return new ReadingDraft.Decision.Summary(name, List.copyOf(up), List.copyOf(down), why, List.copyOf(types));
    }

    // 옛 쉼표 나열 — 한 줄(note)이 없던 형식.
    private static void splitKeywords(String s, List<ReadingDraft.Decision.Summary.Item> into) {
        for (String k : s.split(",")) {
            ReadingDraft.Decision.Summary.Item item = parseKeyword(k);
            if (item != null) {
                into.add(item);
            }
        }
    }

    private static ReadingDraft.Decision.Summary.Item parseKeyword(String s) {
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = KEYWORD_LINE.matcher(t);
        if (!m.matches() || m.group(1).strip().isEmpty()) {
            return new ReadingDraft.Decision.Summary.Item(t, null, "");
        }
        Integer para = m.group(2) == null ? null : Integer.parseInt(m.group(2));
        String note = m.group(3) == null ? "" : m.group(3).strip();
        return new ReadingDraft.Decision.Summary.Item(m.group(1).strip(), para, note);
    }

    // 정리 카드의 문장 검증 — 각 문장이 원문 문단에 그대로 있어야 한다. 새 문장은 하나까지.
    static boolean keepsSourceSentences(String body, String source) {
        String src = squash(source);
        int foreign = 0;
        int kept = 0;
        for (String sentence : body.split("(?<=[.!?])\\s+")) {
            String s = squash(sentence);
            if (s.isEmpty()) {
                continue;
            }
            if (src.contains(s)) {
                kept++;
            } else {
                foreign++;
            }
        }
        return kept > 0 && foreign <= 1;
    }

    // sol의 대목 핵심 표시가 있는 판이면 그것이 확정이고, 없는 판이면 null이라 luna 값을 쓴다.
    private static Boolean pivotOf(List<Boolean> pivots, int para) {
        if (pivots == null || para < 1 || para > pivots.size()) {
            return null;
        }
        return pivots.get(para - 1);
    }

    private static boolean hingeOf(List<Boolean> hinges, int para) {
        return hinges != null && para >= 1 && para <= hinges.size() && hinges.get(para - 1);
    }

    private static String cardHeader(JsonNode node, Boolean pivotOverride, boolean hinge) {
        String dir = hinge ? "미정" : switch (text(node, "direction")) {
            case "UP" -> "올림";
            case "DOWN" -> "내림";
            default -> "미정";
        };
        boolean pivot = pivotOverride != null ? pivotOverride : node.path("pivot").asBoolean(false);
        return "(가능성: " + dir + (pivot ? ", 핵심" : "") + ")";
    }

    private static final Map<String, Object> CARD_ITEM = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.of(
                    "direction", Map.of("type", "STRING", "enum", List.of("UP", "DOWN", "NONE")),
                    "pivot", Map.of("type", "BOOLEAN"),
                    "title", Map.of("type", "STRING"),
                    "body", Map.of("type", "STRING"))),
            Map.entry("required", List.of("direction", "pivot", "title", "body")),
            Map.entry("propertyOrdering", List.of("direction", "pivot", "title", "body")));

    // 정리표 항목 — 키워드와 그 판단이 들어 있는 문단 번호의 짝.
    private static final Map<String, Object> KEYWORD_ITEM = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.of(
                    "keyword", Map.of("type", "STRING"),
                    "para", Map.of("type", "INTEGER"),
                    "note", Map.of("type", "STRING"))),
            Map.entry("required", List.of("keyword", "para", "note")),
            Map.entry("propertyOrdering", List.of("keyword", "para", "note")));

    // 카드 분류 응답 문법 — card-guide와 1:1. 라벨 카드는 번호만 가리키고(본문은 원문),
    // 정리 카드만 본문을 낸다.
    private static final Map<String, Object> CARD_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.of(
                    "cards", Map.of("type", "ARRAY", "items", Map.ofEntries(
                            Map.entry("type", "OBJECT"),
                            Map.entry("properties", Map.of(
                                    "para", Map.of("type", "INTEGER"),
                                    "direction", Map.of("type", "STRING",
                                            "enum", List.of("UP", "DOWN", "NONE")),
                                    "pivot", Map.of("type", "BOOLEAN"),
                                    "title", Map.of("type", "STRING"))),
                            Map.entry("required", List.of("para", "direction", "pivot", "title")),
                            Map.entry("propertyOrdering",
                                    List.of("para", "direction", "pivot", "title")))),
                    // 게이지 아래 정리표 — 분석에 있는 말로만.
                    "summary", Map.ofEntries(
                            Map.entry("type", "OBJECT"),
                            Map.entry("properties", Map.of(
                                    "name", Map.of("type", "STRING"),
                                    "up", Map.of("type", "ARRAY", "items", KEYWORD_ITEM),
                                    "down", Map.of("type", "ARRAY", "items", KEYWORD_ITEM),
                                    "why", Map.of("type", "STRING"))),
                            Map.entry("required", List.of("name", "up", "down", "why")),
                            Map.entry("propertyOrdering", List.of("name", "up", "down", "why"))),
                    // 등급 — 원석에 등급 줄이 없을 때 이 호출이 잣대로 고른다.
                    "grade", Map.of("type", "STRING",
                            "enum", List.of("VERY_LOW", "LOW", "MID", "HIGH", "VERY_HIGH")))),
            Map.entry("required", List.of("cards", "summary", "grade")),
            Map.entry("propertyOrdering", List.of("cards", "summary", "grade")));

    static final String SUMMARY_MARK = "[재회 정리]";
    static final String UP_MARK = "[올리는 것]";
    static final String DOWN_MARK = "[내리는 것]";
    static final String GRADE_MARK = "[가능성 등급]";
    static final String NAME_MARK = "[이름]";
    static final String WHY_MARK = "[총평]";
    // 이 사연에 겹친 이별의 유형 목록(76판, 사장님 "요거만 판정에 넣으면") — 본문 마지막 대목으로 새던 것을 자리로 받는다.
    static final String TYPES_MARK = "[유형 비교]";
    // 81판: 사연마다 모델이 고른 판단 대상들("- 판단 대상: 판독 결과 — 설명"). 저장 자리는 유형 비교와 같은 목록.
    static final String READ_MARK = "[판독]";
    // "[1]" 줄은 분석의 시작, "[2]" 줄은 판독의 시작(84판) — 줄 하나에 표식만 있거나 표식 뒤에 짧은 말이 붙는다.
    private static final java.util.regex.Pattern SECTION_ONE = java.util.regex.Pattern.compile("(?m)^\\s*\\[1\\][^\\n]*\\n?");
    private static final java.util.regex.Pattern SECTION_TWO = java.util.regex.Pattern.compile("(?m)^\\s*\\[2\\][^\\n]*\\n?");
    // "[판독 1 — 제목]", "[판독 2: 제목]", "[판독 3] 제목" — 번호와 구분 기호를 떼고 제목만.
    private static final java.util.regex.Pattern READ_HEAD = java.util.regex.Pattern
            .compile("^\\[판독\\s*\\d*\\s*[—–\\-:]?\\s*([^\\]]+)\\]\\s*(.*)$|^\\[판독\\s*\\d+\\]\\s*(.+)$");
    // 모델이 판정 구획을 열며 쓰는 제목 줄("# 재회 가능성 판독") — 본문 소제목(##)과 다르다.
    private static final java.util.regex.Pattern SECTION_HEAD = java.util.regex.Pattern
            .compile("^#\\s+.*(판독|재회 가능성|재회 정리).*$");
    // 78판: 목록을 방향이 아니라 등급 기준으로 받는다 — 이 등급인 이유, 한 칸 더(매우 낮음/매우 높음)는 아닌 이유.
    // 저장과 화면은 올림/내림 자리 그대로라 등급을 보고 자리를 정한다(낮음 쪽이면 첫째가 내림, 높음 쪽이면 올림).
    static final String MAIN_MARK = "[이 등급인 이유]";
    static final String COUNTER_MARK = "[한 칸 더는 아닌 이유]";
    // sol이 쓰는 네 말 → 등급. 중간(MID)은 2026-09-15에 뺐다 — 판단을 피하는 자리였다. 옛 저장분의 MID는
    // 화면이 그대로 보여주되 새로 고르지는 못한다.
    private static final Map<String, String> GRADE_WORDS = Map.of(
            "매우 낮음", "VERY_LOW", "낮음", "LOW", "높음", "HIGH", "매우 높음", "VERY_HIGH");

    // sol이 본문 뒤에 쓴 이름, 총평, 목록, 등급(같은 호출). 본문은 첫 표식 앞까지. 없는 것은 null이나 빈 목록.
    record SolList(String body, List<String> up, List<String> down, String grade, String name, String why,
                   List<String> types) {
        static final SolList EMPTY = new SolList("", List.of(), List.of(), null, null, null, List.of());

        SolList(String body, List<String> up, List<String> down, String grade) {
            this(body, up, down, grade, null, null, List.of());
        }

        SolList(String body, List<String> up, List<String> down, String grade, String name, String why) {
            this(body, up, down, grade, name, why, List.of());
        }

        boolean present() {
            return !up.isEmpty() || !down.isEmpty() || !types.isEmpty() || (name != null && !name.isBlank())
                    || (why != null && !why.isBlank());
        }
    }

    // 항목 줄의 장식(콜론, 대시, 번호)을 뗀 본문.
    private static String itemText(String s) {
        return s.strip().replaceFirst("^[:：]\\s*", "").replaceFirst("^[-*]\\s*", "")
                .replaceFirst("^\\d+[.)]\\s*", "").strip();
    }

    // "[가능성 등급] 높음" 줄의 말을 등급으로. 긴 말(매우 낮음)부터 맞춰 "낮음"에 잘못 걸리지 않게 한다.
    static String gradeWord(String line) {
        String t = line.substring(GRADE_MARK.length()).replace(":", " ").strip();
        for (String word : List.of("매우 낮음", "매우 높음", "낮음", "높음")) {
            if (t.contains(word)) {
                return GRADE_WORDS.get(word);
            }
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(VERY_LOW|VERY_HIGH|LOW|HIGH)").matcher(t);
        return m.find() ? m.group(1) : null;
    }

    // [올리는 것]/[내리는 것] 표식에서 본문과 목록을 가른다. 표식이 없으면 전부 본문.
    static SolList splitSolList(String analysis) {
        if (analysis == null) {
            return SolList.EMPTY;
        }
        // [1] 분석 / [2] 판독 경계(84판). 경계가 있으면 [2] 앞은 통째로 본문이고 뒤는 표식만 읽는다 —
        // 본문이 판독 안으로 들어가거나(10:11 실측, ## 대목 여섯이 [판독] 밑에) 판독이 본문으로 새는 일이 없다.
        String prefixBody = null;
        java.util.regex.Matcher sec = SECTION_TWO.matcher(analysis);
        if (sec.find()) {
            prefixBody = SECTION_ONE.matcher(analysis.substring(0, sec.start())).replaceAll("").strip();
            analysis = analysis.substring(sec.end());
        }
        boolean strict = prefixBody != null;
        int up = analysis.indexOf(UP_MARK);
        int down = analysis.indexOf(DOWN_MARK);
        int gradeAt = analysis.indexOf(GRADE_MARK);
        int nameAt = analysis.indexOf(NAME_MARK);
        int whyAt = analysis.indexOf(WHY_MARK);
        int typesAt = analysis.indexOf(TYPES_MARK);
        int readAt = analysis.indexOf(READ_MARK);
        int mainAt = analysis.indexOf(MAIN_MARK);
        int counterAt = analysis.indexOf(COUNTER_MARK);
        int cut = java.util.stream.IntStream.of(up, down, gradeAt, nameAt, whyAt, typesAt, readAt, mainAt, counterAt)
                .filter(i -> i >= 0).min().orElse(-1);
        if (cut < 0) {
            return new SolList(strict ? prefixBody : analysis, List.of(), List.of(), null);
        }
        List<String> ups = new ArrayList<>();
        List<String> downs = new ArrayList<>();
        List<String> types = new ArrayList<>();
        List<String> mains = new ArrayList<>();
        List<String> counters = new ArrayList<>();
        List<String> current = null;
        String grade = null;
        StringBuilder name = new StringBuilder();
        StringBuilder why = new StringBuilder();
        StringBuilder text = null;
        // 총평을 본문보다 먼저 쓰는 판(75판)에서는 등급 줄 뒤나 소제목부터가 본문이다 — 표식 앞 글과 이어 붙인다.
        StringBuilder tail = new StringBuilder();
        boolean inTail = false;
        // "[판독 1 — 제목]" 머리줄 + 아래 문단 꼴로 쓰는 판(81판 실측) — 머리줄의 제목과 문단을 한 항목으로 접는다.
        String readTitle = null;
        StringBuilder readDesc = new StringBuilder();
        for (String line : analysis.substring(cut).split("\\n")) {
            String s = line.strip();
            if (readTitle != null && !s.startsWith("[") && !s.startsWith("#") && !s.startsWith("판정 등급")) {
                if (!s.isEmpty()) {
                    if (readDesc.length() > 0) {
                        readDesc.append(' ');
                    }
                    readDesc.append(s);
                }
                continue;
            }
            if (readTitle != null) {
                if (types.size() < 6) {
                    types.add(readDesc.length() == 0 ? readTitle : readTitle + " — " + readDesc);
                }
                readTitle = null;
                readDesc.setLength(0);
            }
            java.util.regex.Matcher readHead = READ_HEAD.matcher(s);
            if (readHead.matches()) {
                readTitle = (readHead.group(1) != null ? readHead.group(1) : readHead.group(3)).strip();
                current = null;
                text = null;
                inTail = false;
                continue;
            }
            if (s.startsWith(UP_MARK) || s.startsWith(DOWN_MARK) || s.startsWith(TYPES_MARK)
                    || s.startsWith(READ_MARK) || s.startsWith(MAIN_MARK) || s.startsWith(COUNTER_MARK)) {
                // 표식과 같은 줄에 첫 항목을 붙여 쓰는 판이 있다("[올리는 것] - …", 2026-09-15 실측).
                String mark = s.startsWith(UP_MARK) ? UP_MARK : s.startsWith(DOWN_MARK) ? DOWN_MARK
                        : s.startsWith(TYPES_MARK) ? TYPES_MARK : s.startsWith(READ_MARK) ? READ_MARK
                        : s.startsWith(MAIN_MARK) ? MAIN_MARK : COUNTER_MARK;
                current = mark.equals(UP_MARK) ? ups : mark.equals(DOWN_MARK) ? downs
                        : mark.equals(TYPES_MARK) || mark.equals(READ_MARK) ? types
                        : mark.equals(MAIN_MARK) ? mains : counters;
                text = null;
                inTail = false;
                String rest = itemText(s.substring(mark.length()));
                if (!rest.isEmpty()) {
                    current.add(rest);
                }
            } else if (s.startsWith(GRADE_MARK)) {
                current = null;
                text = null;
                grade = gradeWord(s);
                inTail = true;
            } else if (s.startsWith(NAME_MARK) || s.startsWith(WHY_MARK)) {
                // 표식 뒤 같은 줄의 말도 받고, 다음 줄들은 다음 표식 전까지 이어 붙인다.
                current = null;
                inTail = false;
                text = s.startsWith(NAME_MARK) ? name : why;
                String rest = itemText(s.substring(s.startsWith(NAME_MARK) ? NAME_MARK.length() : WHY_MARK.length()));
                if (!rest.isEmpty()) {
                    text.append(rest);
                }
            } else if (s.startsWith("#")) {
                if (strict) {
                    continue;
                }
                current = null;
                text = null;
                inTail = true;
                if (!SECTION_HEAD.matcher(s).matches()) {
                    tail.append(line).append('\n');
                }
            } else if (inTail) {
                if (!s.equals("---")) {
                    tail.append(line).append('\n');
                }
            } else if (s.startsWith("[") || s.startsWith("판정 등급")) {
                current = null;
                text = null;
                // "[이름] 짧은 이름"을 "[짧은 이름]"으로 쓰는 판이 있다(81판 실측). 표식이 아닌 짧은 대괄호 줄은
                // 이름이 아직 없을 때만 이름으로 받는다.
                if (name.length() == 0 && s.endsWith("]") && s.length() <= 42 && !s.contains("정리")) {
                    name.append(s, 1, s.length() - 1);
                }
            } else if (text != null && !s.isEmpty()) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(s);
            } else if (current != null && !s.isEmpty()) {
                if (strict || s.startsWith("-") || s.startsWith("*") || s.matches("^\\d+[.)].*")) {
                    String t = itemText(s);
                    if (!t.isEmpty() && current.size() < 6) {
                        current.add(t);
                    }
                } else {
                    // 불릿이 아닌 줄은 목록의 끝이고 본문의 시작이다(78판: 목록 뒤에 본문).
                    current = null;
                    inTail = true;
                    tail.append(line).append('\n');
                }
            }
        }
        // 등급 기준 목록을 올림/내림 자리에 놓는다 — 낮음 쪽은 이 등급인 이유가 내림, 높음 쪽은 올림.
        if (!mains.isEmpty() || !counters.isEmpty()) {
            boolean highSide = "HIGH".equals(grade) || "VERY_HIGH".equals(grade);
            (highSide ? ups : downs).addAll(mains);
            (highSide ? downs : ups).addAll(counters);
        }
        if (readTitle != null && types.size() < 6) {
            types.add(readDesc.length() == 0 ? readTitle : readTitle + " — " + readDesc);
        }
        String head = strict ? prefixBody : analysis.substring(0, cut).strip();
        if (strict) {
            tail.setLength(0);
        }
        // 본문 끝의 구획 제목("---", "# 재회 가능성 판독")은 모델이 판정 구획을 열며 쓴 것 — 본문이 아니다.
        while (true) {
            int nl = head.lastIndexOf('\n');
            String last = head.substring(nl + 1).strip();
            if (last.equals("---") || SECTION_HEAD.matcher(last).matches()) {
                head = nl < 0 ? "" : head.substring(0, nl).stripTrailing();
            } else {
                break;
            }
        }
        // 본문 끝에 "[짧은 이름]"만 남기고 [이름] 표식을 안 쓴 판(81판 실측) — 첫 표식 앞이라 위 루프에 안 걸린다.
        int lastNl = head.lastIndexOf('\n');
        String lastLine = head.substring(lastNl + 1).strip();
        if (name.length() == 0 && lastLine.startsWith("[") && lastLine.endsWith("]")
                && lastLine.length() <= 42 && !lastLine.contains("정리")) {
            name.append(lastLine, 1, lastLine.length() - 1);
            head = lastNl < 0 ? "" : head.substring(0, lastNl).strip();
        }
        String rest = tail.toString().strip();
        String body = head.isEmpty() ? rest : rest.isEmpty() ? head : head + "\n\n" + rest;
        return new SolList(body, List.copyOf(ups), List.copyOf(downs), grade,
                name.length() == 0 ? null : name.toString().strip(),
                why.length() == 0 ? null : why.toString().strip(), List.copyOf(types));
    }

    // 분류가 없거나 실패한 판에서도 sol 목록은 잃지 않는다 — 이름과 이유 없는 정리표로 붙인다.
    private String withSolList(String body, SolList solList) {
        if (body == null || solList == null || !solList.present() || body.contains(SUMMARY_MARK)) {
            return body;
        }
        String summary = renderSummary(null, solList);
        if (summary == null) {
            return body;
        }
        // 등급 줄이 이미 붙어 있으면 그 앞에 끼운다 — 원석의 순서는 본문, 정리표, 등급.
        int gradeAt = body.lastIndexOf("판정 등급");
        if (gradeAt >= 0) {
            return body.substring(0, gradeAt).stripTrailing() + "\n\n" + summary + "\n\n"
                    + body.substring(gradeAt).strip();
        }
        return body.stripTrailing() + "\n\n" + summary;
    }


    private CompletableFuture<String> presentCall(List<ChatMessage> prompt) {
        if ("openai".equalsIgnoreCase(readingProperties.getProvider())) {
            return openAiClient.generateReadingText(prompt, "현재화");
        }
        return llmClient.generateJsonDeep(prompt, null);
    }

    private CompletableFuture<String> verdictCall(List<ChatMessage> prompt) {
        if ("openai".equalsIgnoreCase(readingProperties.getProvider())) {
            return openAiClient.generateVerdictText(prompt);
        }
        return llmClient.generateJsonDeep(prompt, null);
    }

    private List<ChatMessage> verdictPrompt(String guide, String input) {
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(guide));
        prompt.add(ChatMessage.user(input));
        return prompt;
    }

    // 종합은 판정과 같은 모델을 쓴다 — 가지들의 결론을 가르는 일도 판정이다.
    private CompletableFuture<String> synthesisCall(List<ChatMessage> prompt) {
        if ("openai".equalsIgnoreCase(readingProperties.getProvider())) {
            return openAiClient.generateVerdictText(prompt);
        }
        return llmClient.generateJsonDeep(prompt, null);
    }

    // 품질 게이트 — 판독이 사건 재진술 + 일반론에 그치면 1회 재작성시킨다. 심사자는 정답을
    // 쓰지 않는다(답안 슬롯 회귀 방지). 첫 줄에 REVISE가 없으면 통과로 접는다(파이프라인을
    // 심사 형식 이탈로 막지 않는다). 심사문과 재작성 전 판독은 원석에 함께 보관.
    private CompletableFuture<String> critiqueQuietly(String verdict, String story,
                                                      String analysis, StringBuilder criticLog) {
        String criticGuide = readingProperties.getVerdictCriticGuide();
        String verdictGuide = readingProperties.getVerdictGuide();
        if (verdict.isBlank() || criticGuide == null || criticGuide.isBlank()
                || verdictGuide == null || verdictGuide.isBlank()) {
            return CompletableFuture.completedFuture(verdict);
        }
        List<ChatMessage> criticPrompt = new ArrayList<>();
        criticPrompt.add(ChatMessage.system(criticGuide));
        criticPrompt.add(ChatMessage.user(CRITIC_ASK + "\n\n" + story
                + "\n\n[전문가의 분석]\n" + analysis + "\n\n[재회 판독문]\n" + verdict));
        return criticCall(criticPrompt).thenCompose(review -> {
            criticLog.append("\n\n[품질 심사]\n").append(review);
            String firstLine = review.strip().lines().findFirst().orElse("");
            if (!firstLine.contains("REVISE")) {
                return CompletableFuture.completedFuture(verdict);
            }
            criticLog.append("\n\n[재작성 전 판독]\n").append(verdict);
            StringBuilder input = new StringBuilder(VERDICT_ASK).append("\n\n").append(story)
                    .append("\n\n[전문가의 분석]\n").append(analysis)
                    .append("\n\n[이전 판독]\n").append(verdict)
                    .append("\n\n[품질 심사]\n").append(review);
            String reviseNote = readingProperties.getVerdictReviseNote();
            if (reviseNote != null && !reviseNote.isBlank()) {
                input.append("\n\n").append(reviseNote);
            }
            return verdictCall(verdictPrompt(verdictGuide, input.toString()));
        });
    }

    private CompletableFuture<String> criticCall(List<ChatMessage> prompt) {
        if ("openai".equalsIgnoreCase(readingProperties.getProvider())) {
            return openAiClient.generateReadingText(prompt, "심사");
        }
        return llmClient.generateJsonDeep(prompt, null);
    }

    // 판독문을 등급 매퍼에 넘겨 "판정 등급: X" 한 줄을 덧붙인다. 판독과 등급을 한 호출에
    // 두면 등급 토큰이 서사를 끌어당긴다(실측: 같은 사연 VERY_LOW 이탈 2회). 매퍼에는
    // 원 사연을 주지 않는다 — 사연을 다시 보면 독자적 재판정이 시작된다.
    // 상대의 지금 마음과 생각 — 사연과 1장 분석을 받고 등급은 못 본다(등급 줄을 잘라 준다).
    // 지식은 헌법처럼 뒤에 붙는다(시간과 기억 줄이 여기서 쓰인다). 지시가 비면 건너뛴다.
    private CompletableFuture<String> mindQuietly(String analysis, String story) {
        String guide = readingProperties.getMindGuide();
        if (guide == null || guide.isBlank() || analysis == null || analysis.isBlank()) {
            return CompletableFuture.completedFuture("");
        }
        String system = guide;
        String knowledge = readingProperties.getKnowledge();
        if (knowledge != null && !knowledge.isBlank()) {
            system = system + "\n\n[참고 지식]\n" + knowledge;
        }
        int gradeAt = analysis.indexOf("판정 등급");
        String gradeless = gradeAt >= 0 ? analysis.substring(0, gradeAt).stripTrailing() : analysis;
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(system));
        prompt.add(ChatMessage.user(MIND_ASK + "\n\n" + story
                + "\n\n[전문가의 분석]\n" + gradeless));
        CompletableFuture<String> out;
        if ("openai".equalsIgnoreCase(readingProperties.getProvider())) {
            out = openAiClient.generateReadingText(prompt, "마음");
        } else {
            out = llmClient.generateJsonDeep(prompt, null);
        }
        // 실패해도 판독은 간다 — 마음 장은 부록이다.
        return out.exceptionally(e -> {
            log.warn("상대 마음 호출 실패 — 판독은 계속 {}", e.toString());
            return "";
        }).thenApply(s -> s == null ? "" : s.strip());
    }

    // 분석이 등급 줄을 직접 썼으면(구 통합 지시) 매퍼를 건너뛴다 — 두 줄이 되면 파서가 앞 것을 집는다.
    private CompletableFuture<String> gradeAnalysisQuietly(String analysis) {
        if (analysis == null || analysis.contains("판정 등급")) {
            return CompletableFuture.completedFuture(analysis);
        }
        return gradeQuietly(analysis);
    }

    private CompletableFuture<String> gradeQuietly(String verdict) {
        String gradeGuide = readingProperties.getGradeGuide();
        if (verdict.isBlank() || gradeGuide == null || gradeGuide.isBlank()) {
            return CompletableFuture.completedFuture(verdict);
        }
        List<ChatMessage> prompt = new ArrayList<>();
        prompt.add(ChatMessage.system(gradeGuide));
        prompt.add(ChatMessage.user(GRADE_ASK + "\n\n" + verdict));
        CompletableFuture<String> graded;
        if ("openai".equalsIgnoreCase(readingProperties.getProvider())) {
            graded = openAiClient.generateReadingText(prompt, "등급", readingProperties.getGradeModel());
        } else {
            graded = llmClient.generateJsonDeep(prompt, null);
        }
        return graded.thenApply(out -> {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("VERY_HIGH|VERY_LOW|HIGH|MID|LOW")
                    .matcher(out == null ? "" : out);
            // 매퍼가 등급을 못 내면 판독문 그대로 — 편집자 폴백이 정한다.
            return m.find() ? verdict + "\n\n판정 등급: " + m.group() : verdict;
        });
    }

    private CompletableFuture<String> editCall(List<ChatMessage> prompt) {
        if ("openai".equalsIgnoreCase(readingProperties.getProvider())) {
            return openAiClient.generateDecisionJson(prompt, EDIT_SCHEMA);
        }
        return llmClient.generateJsonDeep(prompt, EDIT_SCHEMA);
    }

    // 2단 응답 문법 — reading.yml의 편집 지시와 1:1. 스키마가 프롬프트를 이긴다(구조화 출력).
    private static final Map<String, Object> EDIT_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("caseStatus", Map.of("type", "STRING",
                            "enum", List.of("POSSIBLE", "INSUFFICIENT", "REUNITED"))),
                    Map.entry("gateNote", Map.of("type", "STRING", "nullable", true)),
                    Map.entry("analysis", Map.of("type", "ARRAY", "items", blockSchema())),
                    Map.entry("mind", Map.of("type", "ARRAY", "items", blockSchema())),
                    Map.entry("answers", Map.of("type", "ARRAY", "items", Map.ofEntries(
                            Map.entry("type", "OBJECT"),
                            Map.entry("properties", Map.of(
                                    "question", Map.of("type", "STRING"),
                                    "answer", Map.of("type", "STRING"))),
                            Map.entry("required", List.of("question", "answer")),
                            Map.entry("propertyOrdering", List.of("question", "answer"))))),
                    Map.entry("action", Map.of("type", "ARRAY", "items", blockSchema())),
                    Map.entry("outlookLevel", Map.of("type", "STRING",
                            "enum", List.of("VERY_LOW", "LOW", "MID", "HIGH", "VERY_HIGH"))),
                    Map.entry("verdictLine", Map.of("type", "STRING")),
                    Map.entry("verdict", Map.of("type", "ARRAY", "items", Map.ofEntries(
                            Map.entry("type", "OBJECT"),
                            Map.entry("properties", Map.of(
                                    "subtitle", Map.of("type", "STRING"),
                                    "body", Map.of("type", "STRING"),
                                    "direction", Map.of("type", "STRING",
                                            "enum", List.of("UP", "DOWN", "NONE")))),
                            Map.entry("required", List.of("subtitle", "body", "direction")),
                            Map.entry("propertyOrdering",
                                    List.of("subtitle", "body", "direction"))))))),
            Map.entry("required", List.of("caseStatus", "analysis", "mind", "answers",
                    "action", "outlookLevel", "verdictLine", "verdict")),
            Map.entry("propertyOrdering", List.of("caseStatus", "gateNote", "analysis", "mind",
                    "answers", "action", "outlookLevel", "verdictLine", "verdict")));

    private static Map<String, Object> blockSchema() {
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.of(
                        "subtitle", Map.of("type", "STRING"),
                        "body", Map.of("type", "STRING"))),
                Map.entry("required", List.of("subtitle", "body")),
                Map.entry("propertyOrdering", List.of("subtitle", "body")));
    }

    // 1단 산문의 마크다운 기호는 화면에 그대로 새면 안 된다(채팅 실측과 동일) —
    // 2단이 옮기며 남긴 굵게/헤더/인용 기호만 걷어낸다.
    static String stripMd(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("**", "").replace("`", "")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("(?m)^>\\s*", "")
                .strip();
    }

    // 공백을 걷어낸 비교용 본문 — 어미 다듬기 수준의 차이는 무시하고 같은 문단을 잡는다.
    static String squash(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    // NONE은 방향 없는 판단 — direction을 비워 프론트가 그룹 밖에 그리게 한다.
    static String cardDirection(String raw) {
        if ("UP".equals(raw)) {
            return "UP";
        }
        if ("DOWN".equals(raw)) {
            return "DOWN";
        }
        return null;
    }

    private static final java.util.regex.Pattern JUDGMENT_MARK =
            java.util.regex.Pattern.compile("\\(가능성:\\s*(올림|내림|미정)(\\s*,\\s*핵심)?\\)");

    // 방향 + 핵심 여부 — 핵심은 등급을 실제로 만든 결정적 판단의 표시다.
    record JudgmentMark(String direction, boolean pivot) {
    }

    // 1단 분석 문단들에 인라인으로 붙는 (가능성: ...) 표시를 순서대로 뽑는다.
    // 판정 문단은 표시 대상이 아니라 제외하고, 표시가 없으면 빈 리스트 —
    // 구 형식 원석과 폴백 경로는 편집이 정한 방향을 그대로 쓴다.
    static List<JudgmentMark> parseJudgmentMarks(String rawAnalysis) {
        if (rawAnalysis == null) {
            return List.of();
        }
        int end = rawAnalysis.indexOf("[재회 가능성 판정]");
        String section = end >= 0 ? rawAnalysis.substring(0, end) : rawAnalysis;
        List<JudgmentMark> marks = new ArrayList<>();
        java.util.regex.Matcher m = JUDGMENT_MARK.matcher(section);
        while (m.find()) {
            String direction = switch (m.group(1)) {
                case "올림" -> "UP";
                case "내림" -> "DOWN";
                default -> null;
            };
            marks.add(new JudgmentMark(direction, m.group(2) != null));
        }
        return marks;
    }

    static List<String> parseJudgmentDirections(String rawAnalysis) {
        return parseJudgmentMarks(rawAnalysis).stream()
                .map(JudgmentMark::direction).toList();
    }

    // 표시 문단 원문 + 방향 — 방향의 확정자. 편집이 카드 순서를 바꾸거나 무표시 문단을
    // 승격시키면 순서 기반 덮어쓰기가 방향을 오염시킨 실측(2026-09-04, 631)이 있어
    // 내용 매칭으로 카드마다 자기 문단의 방향을 찾아 박는다.
    record MarkedParagraph(String squashedBody, String direction, boolean pivot) {
    }

    static List<MarkedParagraph> parseMarkedParagraphs(String rawAnalysis) {
        if (rawAnalysis == null) {
            return List.of();
        }
        int end = rawAnalysis.indexOf("[재회 가능성 판정]");
        String section = end >= 0 ? rawAnalysis.substring(0, end) : rawAnalysis;
        List<MarkedParagraph> out = new ArrayList<>();
        for (String para : section.split("\\n\\s*\\n")) {
            java.util.regex.Matcher m = JUDGMENT_MARK.matcher(para);
            String direction = null;
            boolean pivot = false;
            boolean found = false;
            while (m.find()) {
                found = true;
                direction = switch (m.group(1)) {
                    case "올림" -> "UP";
                    case "내림" -> "DOWN";
                    default -> null;
                };
                pivot = m.group(2) != null;
            }
            if (found) {
                out.add(new MarkedParagraph(
                        squash(JUDGMENT_MARK.matcher(para).replaceAll("")), direction, pivot));
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern CARD_HEADER =
            java.util.regex.Pattern.compile(
                    "^\\(가능성:\\s*(올림|내림|미정)(\\s*,\\s*핵심)?\\)\\s*(.*)$");

    // [재회 가능성](구 이름: 판을 가른 판단) 섹션의 카드들 — 방향, 핵심, 제목, 본문을 sol이 확정하고 서버가
    // 파싱해 그대로 화면 카드로 쓴다. 편집(luna)의 카드 선별/방향 재량이 반복해서 샌
    // 실측(2026-09-04, 631 방향 오염) 뒤의 코드 확정. 섹션이 없으면 빈 리스트(구 형식 폴백).
    private List<ReadingDraft.Decision.VerdictCard> parseCardSection(String rawAnalysis) {
        if (rawAnalysis == null) {
            return List.of();
        }
        // 섹션 이름 개편(판을 가른 판단 → 재회 가능성) — 구 이름 산출도 같은 경로로 받는다.
        rawAnalysis = rawAnalysis.replace("[판을 가른 판단]", "[재회 가능성]");
        // "쓴 뒤 고친다" 지시에 sol이 초안과 고친 판을 두 벌 출력한 실측이 있다.
        // 다시 쓴 판은 얇아질 수 있으므로(재작성 압축 실측), 벌마다 파싱해 카드 본문
        // 총량이 큰 벌을 집는다 — 총량이 같으면 뒤(고친 판)가 이긴다.
        List<ReadingDraft.Decision.VerdictCard> best = List.of();
        int bestLen = -1;
        int at = rawAnalysis.indexOf("[재회 가능성]");
        while (at >= 0) {
            String section = rawAnalysis.substring(at + "[재회 가능성]".length());
            int end = section.indexOf("[재회 가능성]");
            int gradeAt = section.indexOf("판정 등급");
            if (gradeAt >= 0 && (end < 0 || gradeAt < end)) {
                end = gradeAt;
            }
            int fixAt = section.indexOf("[고친 카드]");
            if (fixAt >= 0 && (end < 0 || fixAt < end)) {
                end = fixAt;
            }
            for (String mark : List.of(NAMES_MARK, SUMMARY_MARK)) {
                int markAt = section.indexOf(mark);
                if (markAt >= 0 && (end < 0 || markAt < end)) {
                    end = markAt;
                }
            }
            if (end >= 0) {
                section = section.substring(0, end);
            }
            List<ReadingDraft.Decision.VerdictCard> cards = parseCards(section);
            int len = cards.stream().mapToInt(c -> c.body().length()).sum();
            if (len >= bestLen) {
                best = cards;
                bestLen = len;
            }
            at = rawAnalysis.indexOf("[재회 가능성]", at + 1);
        }
        return best;
    }

    // 제목 줄과 본문 사이에 빈 줄이 든 산출 변형이 실측됨(카드 전체 소실) —
    // 헤더만 있는 덩이는 카드를 열어두고, 바로 다음의 헤더 없는 덩이를 본문으로 잇는다.
    // 본문이 이미 찬 카드에는 잇지 않는다(등급 줄 앞의 맺음 문장이 딸려오는 것 방지).
    private List<ReadingDraft.Decision.VerdictCard> parseCards(String section) {
        List<ReadingDraft.Decision.VerdictCard> cards = new ArrayList<>();
        String direction = null;
        Boolean pivot = null;
        String title = null;
        String body = "";
        for (String para : section.split("\\n\\s*\\n")) {
            String chunk = para.strip();
            if (chunk.isEmpty()) {
                continue;
            }
            String[] lines = chunk.split("\\n", 2);
            java.util.regex.Matcher m = CARD_HEADER.matcher(lines[0].strip());
            if (m.matches()) {
                addCard(cards, title, body, direction, pivot);
                direction = switch (m.group(1)) {
                    case "올림" -> "UP";
                    case "내림" -> "DOWN";
                    default -> null;
                };
                pivot = m.group(2) != null ? Boolean.TRUE : null;
                title = m.group(3).strip();
                body = lines.length > 1 ? lines[1].strip() : "";
                if (title.isBlank() && !body.isBlank()) {
                    // 제목이 헤더 다음 줄에 온 경우
                    String[] rest = body.split("\\n", 2);
                    title = rest[0].strip();
                    body = rest.length > 1 ? rest[1].strip() : "";
                }
            } else if (title != null && body.isBlank()) {
                body = chunk;
            }
        }
        addCard(cards, title, body, direction, pivot);
        return cards;
    }

    private void addCard(List<ReadingDraft.Decision.VerdictCard> cards, String title,
                         String body, String direction, Boolean pivot) {
        if (title == null || title.isBlank() || body.isBlank() || cards.size() >= 20) {
            return;
        }
        // sol 텍스트가 luna를 거치지 않고 화면으로 가므로 금지 문자(가운뎃점)는 서버가 푼다.
        cards.add(new ReadingDraft.Decision.VerdictCard(
                clip(title.replaceAll("\\.$", "").replace("·", ", "), LIST_TEXT_MAX),
                body.replace("·", ", "), direction, pivot));
    }

    // 분석 본문 안에서 라벨 헤더가 붙은 문단을 카드로 걷는다 — 원본 문단이 곧 카드 본문.
    // (아래에서 정리) 표시 문단은 라벨이 없으므로 여기서 걷히지 않고, [재회 가능성]
    // 구획의 정리본이 mergeCards에서 그 자리를 맡는다.
    private List<ReadingDraft.Decision.VerdictCard> parseInlineCards(String rawAnalysis) {
        if (rawAnalysis == null) {
            return List.of();
        }
        String region = rawAnalysis.replace("[판을 가른 판단]", "[재회 가능성]");
        int start = region.indexOf("[전문가의 분석]");
        if (start >= 0) {
            region = region.substring(start + "[전문가의 분석]".length());
        }
        int sectionAt = region.indexOf("[재회 가능성]");
        if (sectionAt >= 0) {
            region = region.substring(0, sectionAt);
        }
        int gradeAt = region.indexOf("판정 등급");
        if (gradeAt >= 0) {
            region = region.substring(0, gradeAt);
        }
        return parseCards(region);
    }

    // 분석 속 라벨 문단(원본)과 [재회 가능성] 구획(정리본)을 합친다 — 내용이 겹치면
    // 정리본이 라벨 문단을 대체하고, 겹치지 않는 정리본은 뒤에 붙는다(정리 대상이
    // 라벨 없는 문단이었던 정상 경로).
    private List<ReadingDraft.Decision.VerdictCard> mergeCards(
            List<ReadingDraft.Decision.VerdictCard> inline,
            List<ReadingDraft.Decision.VerdictCard> section) {
        if (inline.isEmpty()) {
            return section;
        }
        if (section.isEmpty()) {
            return inline;
        }
        List<ReadingDraft.Decision.VerdictCard> out = new ArrayList<>();
        boolean[] used = new boolean[section.size()];
        for (ReadingDraft.Decision.VerdictCard card : inline) {
            String body = squash(card.body());
            List<ReadingDraft.Decision.VerdictCard> matched = new ArrayList<>();
            for (int i = 0; i < section.size(); i++) {
                if (!used[i] && sharesCardText(squash(section.get(i).body()), List.of(body))) {
                    matched.add(section.get(i));
                    used[i] = true;
                }
            }
            if (matched.isEmpty()) {
                out.add(card);
            } else {
                out.addAll(matched);
            }
        }
        for (int i = 0; i < section.size(); i++) {
            if (!used[i]) {
                out.add(section.get(i));
            }
        }
        return out;
    }

    // [고친 카드] — 점검에 걸린 카드만 sol이 고쳐 다시 쓰는 구획.
    private List<ReadingDraft.Decision.VerdictCard> parseFixedCards(String rawAnalysis) {
        if (rawAnalysis == null) {
            return List.of();
        }
        int at = rawAnalysis.lastIndexOf("[고친 카드]");
        if (at < 0) {
            return List.of();
        }
        String section = rawAnalysis.substring(at + "[고친 카드]".length());
        int gradeAt = section.indexOf("판정 등급");
        if (gradeAt >= 0) {
            section = section.substring(0, gradeAt);
        }
        return parseCards(section);
    }

    // 고친 카드로 원래 카드를 그 자리에서 대체한다 — 내용 대조로 짝을 찾고,
    // 분리(한 카드를 나눔)면 나눈 카드들이 한 장을 대체한다. 짝 없는 고친 카드는
    // 새 판단(승격)일 수 있으므로 채택하지 않고 경고만 남긴다.
    private List<ReadingDraft.Decision.VerdictCard> applyCardFixes(
            List<ReadingDraft.Decision.VerdictCard> cards,
            List<ReadingDraft.Decision.VerdictCard> fixes) {
        if (cards.isEmpty() || fixes.isEmpty()) {
            return cards;
        }
        List<ReadingDraft.Decision.VerdictCard> out = new ArrayList<>();
        boolean[] used = new boolean[fixes.size()];
        for (ReadingDraft.Decision.VerdictCard card : cards) {
            String body = squash(card.body());
            List<ReadingDraft.Decision.VerdictCard> matched = new ArrayList<>();
            for (int i = 0; i < fixes.size(); i++) {
                if (!used[i] && sharesCardText(squash(fixes.get(i).body()), List.of(body))) {
                    matched.add(fixes.get(i));
                    used[i] = true;
                }
            }
            if (matched.isEmpty()) {
                out.add(card);
            } else {
                out.addAll(matched);
            }
        }
        for (int i = 0; i < fixes.size(); i++) {
            if (!used[i]) {
                log.warn("원래 카드와 매칭되지 않는 [고친 카드] — 무시: {}", fixes.get(i).subtitle());
            }
        }
        return out;
    }

    // 분석 문단이 카드에 쓰였는지 — 카드가 문단을 손질/병합해도 잡히게 40자 창으로 대조.
    private static boolean sharesCardText(String paraSquashed, List<String> cardBodies) {
        for (String cb : cardBodies) {
            if (cb.contains(paraSquashed) || paraSquashed.contains(cb)) {
                return true;
            }
            for (int i = 0; i + 40 <= paraSquashed.length(); i += 20) {
                if (cb.contains(paraSquashed.substring(i, i + 40))) {
                    return true;
                }
            }
        }
        return false;
    }

    // 원석에서 [전문가의 분석] 본문만 — 카드 구획과 등급 줄은 뗀다.
    private static String analysisSection(String rawAnalysis) {
        int start = rawAnalysis.indexOf("[전문가의 분석]");
        String section = start >= 0
                ? rawAnalysis.substring(start + "[전문가의 분석]".length()) : rawAnalysis;
        int cardsAt = section.indexOf("[재회 가능성]");
        int oldMarkAt = section.indexOf("[판을 가른 판단]");
        if (oldMarkAt >= 0 && (cardsAt < 0 || oldMarkAt < cardsAt)) {
            cardsAt = oldMarkAt;
        }
        if (cardsAt >= 0) {
            section = section.substring(0, cardsAt);
        }
        // 구획이 없는 산출(라벨만 있는 형식)에서는 등급 줄이 분석 지역에 남는다.
        int gradeCut = section.indexOf("판정 등급");
        if (gradeCut >= 0) {
            section = section.substring(0, gradeCut);
        }
        return section;
    }

    // 소견서 본문 — 분석 문단 전부를 원문 순서대로 두고, 카드가 된 문단에는 그 카드의
    // 방향과 핵심을 붙인다. 정리(reworked)된 문단은 원문 대신 정리본 카드로 선다.
    // 오려내지 않으므로 대목 안에서 문단이 빠져 글이 끊기는 일이 없다(2026-09-07 판형).
    static List<ReadingDraft.Decision.ReadingBlock> buildReadingBlocks(
            String rawAnalysis, List<ReadingDraft.Decision.VerdictCard> cards) {
        return buildReadingBlocks(rawAnalysis, cards, java.util.Map.of());
    }

    // keywords는 분류 호출의 문단 번호(1-based) → 정리표 키워드. 번호는 분류 호출이 본 문단
    // 순서와 같다(둘 다 splitHeaded의 순서).
    static List<ReadingDraft.Decision.ReadingBlock> buildReadingBlocks(
            String rawAnalysis, List<ReadingDraft.Decision.VerdictCard> cards,
            java.util.Map<Integer, String> keywords) {
        return buildReadingBlocks(rawAnalysis, cards, keywords, java.util.Map.of());
    }

    // names는 분류 호출이 붙인 대목 이름(문단 번호 → 이름) — 있으면 sol의 소제목 대신 선다.
    static List<ReadingDraft.Decision.ReadingBlock> buildReadingBlocks(
            String rawAnalysis, List<ReadingDraft.Decision.VerdictCard> cards,
            java.util.Map<Integer, String> keywords, java.util.Map<Integer, String> names) {
        if (rawAnalysis == null) {
            return List.of();
        }
        java.util.Map<String, ReadingDraft.Decision.VerdictCard> exact = new java.util.HashMap<>();
        for (ReadingDraft.Decision.VerdictCard c : cards) {
            String sq = squash(c.body());
            if (!sq.isBlank()) {
                exact.putIfAbsent(sq, c);
            }
        }
        java.util.Set<ReadingDraft.Decision.VerdictCard> used = new java.util.HashSet<>();
        List<ReadingDraft.Decision.ReadingBlock> blocks = new ArrayList<>();
        List<Para> all = splitHeaded(analysisSection(rawAnalysis));
        int paraNo = 0;
        for (Para para : all) {
            paraNo++;
            String keyword = keywords.get(paraNo);
            String body = para.body();
            if (body.isBlank() || blocks.size() >= 60) {
                continue;
            }
            String subtitle = para.heading() == null ? ""
                    : names.getOrDefault(paraNo, para.heading()).replace("·", ", ");
            // 맺음(마지막 문단)은 앞 대목의 표시를 물려받지 않는다 — 대목 밖의 문단이다.
            boolean closing = paraNo == all.size() && all.size() > 1;
            Boolean sectionPivot = para.pivot() && !closing ? Boolean.TRUE : null;
            // (높낮이를 가를 수 있는 것) 대목은 문단 전부가 미정 — 화면이 글 끝 자리로 옮긴다.
            if (para.hinge() && !closing) {
                blocks.add(new ReadingDraft.Decision.ReadingBlock(
                        subtitle, body.replace("(아래에서 정리)", "").strip().replace("·", ", "),
                        "NONE", sectionPivot, keyword));
                continue;
            }
            if (body.contains("(아래에서 정리)")) {
                // 정리본은 원문 문장을 고른 것이라 원문과 부분 겹침으로 찾는다.
                String sq = squash(body.replace("(아래에서 정리)", ""));
                boolean any = false;
                for (ReadingDraft.Decision.VerdictCard c : cards) {
                    if (used.contains(c)) {
                        continue;
                    }
                    String csq = squash(c.body());
                    if (!csq.isBlank() && sharesCardText(csq, List.of(sq))) {
                        used.add(c);
                        blocks.add(new ReadingDraft.Decision.ReadingBlock(
                                any ? "" : subtitle, c.body(), blockDirection(c),
                                c.pivot() != null ? c.pivot() : sectionPivot, any ? null : keyword));
                        any = true;
                    }
                }
                if (any) {
                    continue;
                }
                body = body.replace("(아래에서 정리)", "").strip();
            }
            ReadingDraft.Decision.VerdictCard card = exact.get(squash(body));
            if (card != null && !used.contains(card)) {
                used.add(card);
                blocks.add(new ReadingDraft.Decision.ReadingBlock(
                        subtitle, body.replace("·", ", "), blockDirection(card),
                        card.pivot() != null ? card.pivot() : sectionPivot, keyword));
            } else {
                // 키워드는 있는데 라벨이 없는 문단 — 키워드가 곧 표시라 방향 없이 키워드만 붙는다.
                blocks.add(new ReadingDraft.Decision.ReadingBlock(
                        subtitle, body.replace("·", ", "), null, sectionPivot, keyword));
            }
        }
        return blocks;
    }

    // 카드의 null 방향은 미정(NONE) — 소견서에서는 "표시 없음"과 구분해야 해서 문자로 박는다.
    private static String blockDirection(ReadingDraft.Decision.VerdictCard card) {
        return card.direction() == null ? "NONE" : card.direction();
    }

    // [전문가의 분석] 문단을 서버가 직접 배분한다 — 편집(luna)이 분석 장을 요약해
    // 평탄화한 실측(2026-09-05, 631) 뒤의 코드 확정. 첫 문단은 리드(프론트가 게이지
    // 위로 올림), 카드에 쓰인 문단은 제외, 나머지는 원문 그대로 02의 산문이 된다.
    private List<ReadingDraft.Decision.PrologueBlock> buildAnalysisBlocks(
            String rawAnalysis, List<ReadingDraft.Decision.VerdictCard> cards) {
        if (rawAnalysis == null) {
            return List.of();
        }
        String section = analysisSection(rawAnalysis);
        List<String> cardBodies = cards.stream()
                .map(c -> squash(c.body())).filter(s -> !s.isBlank()).toList();
        List<ReadingDraft.Decision.PrologueBlock> blocks = new ArrayList<>();
        int dropped = 0;
        for (Para para : splitHeaded(section)) {
            // 상한은 내용 규칙이 아니라 응답 폭주 안전핀 — 정점 판이 16문단이라 20은 위험권.
            String body = para.body();
            if (body.isBlank() || blocks.size() >= 40) {
                continue;
            }
            // 라벨 헤더가 붙은 문단은 카드 몫이고, (아래에서 정리) 표시 문단은
            // 구획의 정리본이 대체한다 — 둘 다 02 산문에 싣지 않는다.
            if (CARD_HEADER.matcher(body.split("\\n", 2)[0].strip()).matches()
                    || body.contains("(아래에서 정리)")) {
                dropped++;
                continue;
            }
            String sq = squash(body);
            // 완전 일치(라벨 카드가 원문 그대로 가져간 문단)는 길이와 무관하게 제외하고,
            // 부분 겹침 대조는 짧은 문단 오탐을 막기 위해 60자 이상에만 건다.
            if (cardBodies.contains(sq) || (sq.length() >= 60 && sharesCardText(sq, cardBodies))) {
                dropped++;
                continue;
            }
            // 소제목은 sol이 쓴 것 그대로 — 02장의 문단 제목이 된다.
            blocks.add(new ReadingDraft.Decision.PrologueBlock(
                    para.heading() == null ? "" : para.heading().replace("·", ", "),
                    body.replace("·", ", ")));
        }
        log.info("분석 장 서버 배분: 문단 {}개, 카드로 소비 {}개", blocks.size(), dropped);
        return blocks;
    }

    // 결정 호출 없이 원석만으로 화면 재료를 만든다 — 카드와 책갈피와 정리표는 분류 호출이 원석에
    // 써 둔 것이고, 등급은 원석 끝의 "판정 등급" 줄이다. 게이트는 늘 POSSIBLE, 총평과 질문 답변은 없다.
    DirectReading fromRaw(String rawAnalysis) {
        List<ReadingDraft.Decision.VerdictCard> solCards = resolveParaRefs(mergeCards(
                parseInlineCards(rawAnalysis),
                applyCardFixes(parseCardSection(rawAnalysis), parseFixedCards(rawAnalysis))),
                rawAnalysis);
        String level = gradeLineLevel(rawAnalysis);
        ReadingDraft.Decision.Summary summary = parseSummary(rawAnalysis);
        // 카드가 없어도 본문 블록은 만든다 — 분류 호출을 끈 판(2026-09-15)에서 null이면 화면이 "분석 실패"로
        // 보였다. 카드 없는 문단은 방향 없이 소제목과 본문만 실린다.
        List<ReadingDraft.Decision.ReadingBlock> readingBlocks = buildReadingBlocks(
                rawAnalysis, solCards, keywordsByPara(summary), parseNames(rawAnalysis));
        List<ReadingDraft.Decision.PrologueBlock> analysisBlocks =
                solCards.isEmpty() ? List.of() : buildAnalysisBlocks(rawAnalysis, solCards);
        ReadingDraft.Decision decision = new ReadingDraft.Decision(
                null, null, null, null, analysisBlocks, List.of(),
                null, List.of(), null,
                level, null, OUTLOOK_SCORES.get(level), solCards, List.of(), List.of(),
                readingBlocks, summary);
        log.info("판독(결정 호출 없음): 카드 {}개, 분석 {}블록, outlook={}",
                solCards.size(), analysisBlocks.size(), level);
        return new DirectReading("POSSIBLE", null, new ReadingDraft(
                "", null, List.of(), "", List.of(), null,
                rawAnalysis, null, null, decision));
    }

    // 원석 끝의 "판정 등급: X" 줄 — 없거나 어휘 밖이면 MID.
    static String gradeLineLevel(String rawAnalysis) {
        if (rawAnalysis == null) {
            return "MID";
        }
        int at = rawAnalysis.lastIndexOf("판정 등급");
        if (at < 0) {
            return "MID";
        }
        String tail = rawAnalysis.substring(at);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(VERY_LOW|VERY_HIGH|LOW|MID|HIGH)").matcher(tail);
        return m.find() ? m.group(1) : "MID";
    }

    private DirectReading parseEdited(String json, String rawAnalysis) {
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
            String status = root.path("caseStatus").asText("POSSIBLE");
            String note = text(root, "gateNote");
            if (!"POSSIBLE".equals(status)) {
                log.info("판독 게이트: {} — 리포트 생략", status);
                return new DirectReading(status, note, null);
            }

            // 상한은 내용 규칙이 아니라 응답 폭주 방지 안전핀 — 지시의 개수 제한은 뺐다.
            // 표시 문단은 카드로 가고 analysis 장에 중복 게재하지 않으므로, 표시가 많은
            // 판에서는 analysis와 mind가 비는 것이 정상이다 — 빈 배열만으로 실패 처리하지
            // 않고, 카드까지 아무것도 없을 때만 아래에서 실패로 접는다.
            List<ReadingDraft.Decision.PrologueBlock> analysisBlocks =
                    blocks(root.path("analysis"), 10);
            // mind는 설계상 빈 배열이 정상(카드가 흡수) — 비어 있다고 경고하지 않는다.
            List<ReadingDraft.Decision.PrologueBlock> mindBlocks = blocks(root.path("mind"), 6);
            List<ReadingDraft.Decision.PrologueBlock> actionBlocks = blocks(root.path("action"), 6);

            String level = root.path("outlookLevel").asText("");
            if (!OUTLOOK_SCORES.containsKey(level)) {
                log.warn("outlookLevel이 어휘 밖({}) — MID로 접음", level);
                level = "MID";
            }
            List<ReadingDraft.Decision.VerdictCard> verdictBlocks = new ArrayList<>();
            for (JsonNode node : root.path("verdict")) {
                String subtitle = stripMd(text(node, "subtitle"));
                String body = stripMd(text(node, "body"));
                // 본문은 선택 — 소제목만으로 충분한 카드는 본문 없이 선다(반복 방지).
                if (!subtitle.isBlank() && verdictBlocks.size() < 20) {
                    verdictBlocks.add(new ReadingDraft.Decision.VerdictCard(
                            clip(subtitle, LIST_TEXT_MAX), body,
                            cardDirection(text(node, "direction")), null));
                }
            }
            if (verdictBlocks.isEmpty()) {
                log.warn("2단 편집에 가능성 논증 카드가 없음 — verdictLine만 표시");
            }
            if (analysisBlocks.isEmpty() && verdictBlocks.isEmpty()) {
                log.warn("2단 편집에 분석 블록도 카드도 없음 — 판독 실패 처리");
                throw new LlmException();
            }
            // 카드의 확정자는 편집이 아니라 1단이다. [재회 가능성] 섹션이 있으면
            // 서버가 직접 파싱한 카드로 통째 대체한다 — 편집의 선별/방향/제목 재량 소멸.
            List<ReadingDraft.Decision.VerdictCard> solCards = resolveParaRefs(mergeCards(
                    parseInlineCards(rawAnalysis),
                    applyCardFixes(parseCardSection(rawAnalysis), parseFixedCards(rawAnalysis))),
                    rawAnalysis);
            if (!solCards.isEmpty()) {
                if (verdictBlocks.size() != solCards.size()) {
                    log.info("sol 카드 {}개 vs 편집 카드 {}개 — sol 카드로 대체",
                            solCards.size(), verdictBlocks.size());
                }
                verdictBlocks.clear();
                verdictBlocks.addAll(solCards);
                // 분석 장도 편집의 요약본 대신 원문단을 서버가 직접 배분한다.
                analysisBlocks = buildAnalysisBlocks(rawAnalysis, solCards);
            }
            // 구 형식(인라인 표시) 폴백 — 편집이 카드 순서를 바꾸거나 무표시 문단을
            // 승격시켜도 방향이 오염되지 않게, 카드 본문을 표시 문단과 내용으로 대조해
            // 그 문단의 방향을 박는다. 어느 표시 문단과도 안 맞는 카드는 승격으로 보고
            // analysis 장으로 되돌리고, 카드로 못 온 표시 문단은 경고만 남긴다.
            List<MarkedParagraph> markedParas =
                    solCards.isEmpty() ? parseMarkedParagraphs(rawAnalysis) : List.of();
            if (!markedParas.isEmpty()) {
                analysisBlocks = new ArrayList<>(analysisBlocks);
                List<ReadingDraft.Decision.VerdictCard> kept = new ArrayList<>();
                java.util.Set<Integer> usedParas = new java.util.HashSet<>();
                for (ReadingDraft.Decision.VerdictCard card : verdictBlocks) {
                    String body = squash(card.body());
                    String key = body.length() > 60 ? body.substring(0, 60) : body;
                    MarkedParagraph hit = null;
                    for (int p = 0; p < markedParas.size(); p++) {
                        MarkedParagraph mp = markedParas.get(p);
                        String pKey = mp.squashedBody().length() > 60
                                ? mp.squashedBody().substring(0, 60) : mp.squashedBody();
                        if ((!key.isBlank() && mp.squashedBody().contains(key))
                                || (!pKey.isBlank() && body.contains(pKey))) {
                            hit = mp;
                            usedParas.add(p);
                            break;
                        }
                    }
                    if (hit != null) {
                        kept.add(new ReadingDraft.Decision.VerdictCard(
                                card.subtitle(), card.body(), hit.direction(),
                                hit.pivot() ? Boolean.TRUE : null));
                    } else {
                        log.warn("표시 문단과 매칭되지 않는 카드(승격 의심) — analysis로 되돌림: {}",
                                card.subtitle());
                        analysisBlocks.add(new ReadingDraft.Decision.PrologueBlock(
                                card.subtitle(), card.body()));
                    }
                }
                verdictBlocks.clear();
                verdictBlocks.addAll(kept);
                if (usedParas.size() < markedParas.size()) {
                    log.warn("표시 문단 {}개 중 {}개만 카드로 도달 — 카드 누락 의심",
                            markedParas.size(), usedParas.size());
                }
            }
            // 카드 body나 verdictLine(맺음 문장)으로 실린 문단이 analysis 장에 또 실리면
            // 화면에 같은 글이 두 번 보인다. 편집 지시(중복 금지)가 흘린 실측이 있어
            // 서버가 확정자로 걸러낸다.
            String verdictLine = stripMd(text(root, "verdictLine"));
            if (!analysisBlocks.isEmpty()) {
                List<String> cardBodies = new ArrayList<>(verdictBlocks.stream()
                        .map(c -> squash(c.body())).filter(s -> s.length() >= 60).toList());
                String vl = squash(verdictLine);
                if (vl.length() >= 60) {
                    cardBodies.add(vl);
                }
                int before = analysisBlocks.size();
                analysisBlocks.removeIf(b -> {
                    String body = squash(b.body());
                    if (body.length() < 60) {
                        return false;
                    }
                    for (String cb : cardBodies) {
                        // 카드 본문이 원문단의 손질본이면 포함 관계가 깨질 수 있어
                        // 첫 60자 동일도 같은 글로 본다.
                        if (cb.contains(body) || body.contains(cb)
                                || (cb.length() >= 60
                                        && cb.substring(0, 60).equals(body.substring(0, 60)))) {
                            return true;
                        }
                    }
                    return false;
                });
                if (analysisBlocks.size() < before) {
                    log.info("카드와 중복된 분석 블록 {}개 제거", before - analysisBlocks.size());
                }
            }
            List<ReadingDraft.Decision.Answer> answers = new ArrayList<>();
            for (JsonNode node : root.path("answers")) {
                String question = text(node, "question");
                String answer = stripMd(text(node, "answer"));
                if (!question.isBlank() && !answer.isBlank()) {
                    answers.add(new ReadingDraft.Decision.Answer(
                            clip(question, LIST_TEXT_MAX), answer));
                }
            }

            // 소견서 본문 — sol 카드가 있는 판에서만. 카드를 제자리에 표시하려면 원문 순서와
            // 카드 매칭이 둘 다 필요해서 서버가 여기서 확정한다.
            ReadingDraft.Decision.Summary summary = parseSummary(rawAnalysis);
            List<ReadingDraft.Decision.ReadingBlock> readingBlocks =
                    solCards.isEmpty() ? null
                            : buildReadingBlocks(rawAnalysis, solCards, keywordsByPara(summary),
                                    parseNames(rawAnalysis));
            ReadingDraft.Decision decision = new ReadingDraft.Decision(
                    null, null, null, null, analysisBlocks, actionBlocks,
                    null, mindBlocks, null,
                    level, verdictLine,
                    OUTLOOK_SCORES.get(level), verdictBlocks, List.of(), answers, readingBlocks,
                    summary);
            log.info("판독 2단: 카드 {}개, 분석 {}블록, 행동 {}블록, outlook={}",
                    verdictBlocks.size(), analysisBlocks.size(), actionBlocks.size(), level);
            // 원석(1단 산문)은 synthesis 자리에 통째 보관한다 — 화면엔 안 그리고,
            // 재편집과 골든셋 채점의 원본이다.
            return new DirectReading(status, note, new ReadingDraft(
                    "", null, List.of(), "", List.of(), null,
                    rawAnalysis, null, null, decision));
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("2단 편집 JSON 파싱 실패 (본문 길이 {}자)",
                    json == null ? 0 : json.length(), e);
            throw new LlmException();
        }
    }

    private List<ReadingDraft.Decision.PrologueBlock> blocks(JsonNode array, int max) {
        List<ReadingDraft.Decision.PrologueBlock> out = new ArrayList<>();
        for (JsonNode node : array) {
            String subtitle = stripMd(text(node, "subtitle"));
            String body = stripMd(text(node, "body"));
            if (!subtitle.isBlank() && !body.isBlank() && out.size() < max) {
                out.add(new ReadingDraft.Decision.PrologueBlock(
                        clip(subtitle, LIST_TEXT_MAX), body));
            }
        }
        return out;
    }

    // 같은 입력을 여러 번 돌려 출력 분산을 재는 실험(판독반복.py)용 — 마지막 판독 입력을
    // 로컬 파일로 남긴다. 실패해도 판독에는 영향 없다(관찰 장치일 뿐).
    private void dumpPacketQuietly(List<ChatMessage> prompt) {
        try {
            // 시스템 차례(헌법과 그 뒤에 딸린 것)도 같이 남긴다 — 모델에 실제로 무엇이 갔는지는 이 파일이
            // 유일한 증거다(2026-09-16, 사장님 "이 리딩만 전달되는지 체크").
            StringBuilder sb = new StringBuilder();
            for (ChatMessage m : prompt) {
                sb.append("[").append(m.role() == com.threeam.llm.LlmRole.USER ? "user" : "system")
                        .append("]\n").append(m.content()).append("\n\n");
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of("logs", "last-reading-packet.txt"),
                    sb.toString().stripTrailing());
        } catch (Exception e) {
            log.debug("판독 packet 덤프 실패(무시)");
        }
    }

    // 판독만 프로바이더 분기 — LLM_READING_PROVIDER로 이 호출만 갈아탄다.
    // 진단(1호출)과 채팅은 이 값과 무관하게 기존 프로바이더를 유지하고, 비우면 제미니로 간다.
    private CompletableFuture<String> call(List<ChatMessage> prompt, boolean baseline) {
        Map<String, Object> schema = baseline ? BASELINE_SCHEMA : RESPONSE_SCHEMA;
        String provider = readingProperties.getProvider();
        if ("anthropic".equalsIgnoreCase(provider)) {
            return anthropicClient.generateReadingJson(prompt, schema);
        }
        if ("openai".equalsIgnoreCase(provider)) {
            return openAiClient.generateReadingJson(prompt, schema);
        }
        return llmClient.generateJsonDeep(prompt, schema);
    }

    // 임상/규범 어휘 린트 — 프롬프트에서 출처를 전부 지워도 재발해 모델 습성으로 확정된
    // 단어들(실측 2026-08-15). 규칙은 새지만 검사는 안 샌다 — 걸리면 1회만 다시 쓰게 한다.
    // 재생성마저 실패하면 원본을 쓴다(단어 하나 때문에 판독을 통째로 버리지 않는다).
    private static final List<String> LINT_BANNED = List.of(
            "방어기제", "방어 기제", "방어벽", "방어선", "셔터", "임계점", "리트머스", "급성",
            "정서적 범람", "감정적 범람", "관계 효능감", "파트너 반응성", "안전기지", "안전 기지",
            "자기보호적", "회피 기제", "경계 침범", "경계를 침범", "경계 존중", "경계를 존중",
            "유저분", "유저님", "내담자", "사연자분", "자율성");

    // verdict가 답이 아니라 목차 꼴로 선 경우를 잡는다. 어휘와 같은 층이다 — 지시로 다섯 배치
    // 연속 재발해 모델 습성으로 봤다. 기준은 하나: 답이 서술문으로 끝나는가.
    // "~한 이유", "~의 진짜 의미", "왜 ~했을까", 소재 두 개 나열이 전부 이 하나에 걸린다.
    static boolean indexShapedVerdict(String verdict) {
        if (verdict == null) {
            return false;
        }
        String s = verdict.strip().replaceAll("[\\s.!?\"'“”’]+$", "");
        return !s.isEmpty()
                && !(s.endsWith("다") || s.endsWith("요") || s.endsWith("죠"));
    }

    // 걸린 문장을 재작성 지시에 그대로 옮기지 않고 순번만 넘긴다 — 지시문에 적은 나쁜 꼴이
    // 다음 출력으로 새는 게 다섯 번 실측됐다. 순번은 새지 않는다.
    // question은 반대로 물음이어야 하므로 물음표로 끝나지 않으면 같이 잡는다.
    private List<Integer> badChapterPositions(String json) {
        List<Integer> out = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
            int no = 1;
            for (JsonNode node : root.path("analysisChapters")) {
                String question = text(node, "question");
                boolean notAsked = !question.isBlank() && !question.strip().endsWith("?");
                if (indexShapedVerdict(text(node, "verdict")) || notAsked) {
                    out.add(no);
                }
                no++;
            }
        } catch (Exception e) {
            // 잘린 응답이면 이 검사만 건너뛴다 — 어휘 검사와 파서는 그대로 돈다.
            log.debug("장 린트 건너뜀 — 판독 JSON 파싱 실패");
        }
        return out;
    }

    private CompletableFuture<String> lintRetry(String json, List<ChatMessage> prompt) {
        List<String> hits = LINT_BANNED.stream().filter(w -> json != null && json.contains(w)).toList();
        List<Integer> badTitles = json == null ? List.of() : badChapterPositions(json);
        if (hits.isEmpty() && badTitles.isEmpty()) {
            return CompletableFuture.completedFuture(json);
        }
        log.warn("판독 린트: 금지어 {}건({}), 질문/답 꼴 어긋남 {}장 — 1회 재생성",
                hits.size(), String.join(", ", hits), badTitles.size());
        StringBuilder ask = new StringBuilder("[재작성 지시] 방금 쓴 판독에서 아래만 고쳐 같은 분석을"
                + " 같은 JSON 형식으로 다시 써라. 나머지 내용과 판정은 그대로 유지한다.");
        if (!hits.isEmpty()) {
            ask.append("\n- 다음 표현이 있었다: ").append(String.join(", ", hits))
                    .append(". 전부 그 자리에서 실제로 일어난 일을 말하는 일상어로 바꿔라.");
        }
        if (!badTitles.isEmpty()) {
            ask.append("\n- 심층 장 ")
                    .append(badTitles.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse(""))
                    .append("번째의 question과 verdict를 다시 써라. question은 사연자가 속으로 묻는")
                    .append(" 말이라 물음표로 끝나야 하고, verdict는 그 물음에 대한 답이라 서술문으로")
                    .append(" 끝나야 한다. 둘의 자리가 바뀌거나 답이 명사구로 서면 목차가 된다.");
        }
        List<ChatMessage> retry = new ArrayList<>(prompt);
        retry.add(ChatMessage.user(ask.toString()));
        return call(retry, false).exceptionally(ex -> {
            log.warn("판독 린트 재생성 실패 — 원본 사용", ex);
            return json;
        });
    }

    // 축 선택 — 진단이 확정한 판의 구조로 확인 축 2~4개를 고른다. 축은 결론이 아니라 볼 곳이라
    // 오분류여도 크게 다치지 않고, 매칭 실패 시 빈 문자열(공용 코어만)로 지금과 동일하게 동작한다.
    private String axisBlock(Assessment saved, ReunionDiagnosis diagnosis) {
        Map<String, String> axes = readingProperties.getAxes();
        if (axes == null || axes.isEmpty()) {
            return "";
        }
        List<String> keys = new ArrayList<>();
        ReunionDiagnosis.MatchProfileItem mp = diagnosis.matchProfile();
        boolean partnerNew = mp != null && Boolean.TRUE.equals(mp.partnerHasNew());
        boolean blocked = mp != null && "차단".equals(mp.contactState());
        // 상태 오버레이 먼저 — 유형보다 판을 더 강하게 규정한다.
        if (partnerNew) {
            keys.addAll(List.of("change-first", "sober-choice", "kept-line"));
        }
        if (blocked) {
            keys.add("recontact-calculus");
        }
        // 오해 교정은 유형이 아니라 재료가 조건이다 — 유저 해석이 실제로 있을 때만 걸린다.
        // 틀린 해석을 두면 나머지 판독이 소용없어 유형 축보다 앞에 세운다.
        if (diagnosis.userFocus() != null && !diagnosis.userFocus().isEmpty()) {
            keys.add("misread-correction");
        }
        if (saved.getBreakupType() != null) {
            switch (saved.getBreakupType()) {
                // 노력의 크기는 별도 축으로 싣지 않는다 — 축으로 실으면 장이 되고, 그 축으로
                // 장을 만드는 것 자체가 골든셋 불합격이다. fusion-root와 size-verdict 안에
                // 한 대목으로 접어 넣었다(실측 599: 축으로 싣자 노력 목록 장이 생겼다).
                case BURNOUT -> keys.addAll(List.of("fusion-root", "standard-flip",
                        "outside-inside"));
                case IMPULSIVE -> keys.addAll(List.of("partner-view-walk", "seesaw",
                        "reaction-size", "grace-scale"));
                case SITUATIONAL, EXTERNAL ->
                        keys.addAll(List.of("love-vs-energy", "calm-acceptance", "when-better"));
                case TRUST_BROKEN -> keys.addAll(List.of("held-heart", "grace-reality",
                        "reaction-size", "recontact-calculus"));
                case RESOLVED -> keys.addAll(List.of("size-verdict", "sns-value", "fusion-root"));
                case FADED -> keys.addAll(List.of("size-verdict", "sns-value"));
                case TRANSFER -> keys.addAll(List.of("sober-choice", "change-first"));
            }
        }
        // 상한 5 — 축은 서로 다른 구조를 보라는 것이라 섞여도 희석이 적지만, 늘리면
        // 결국 전 유형을 한 프롬프트에 담던 옛 문제로 돌아간다. 여기가 그 경계다.
        List<String> selected = keys.stream().distinct()
                .filter(axes::containsKey).limit(5).toList();
        if (selected.isEmpty()) {
            return "";
        }
        log.info("판독 축 선택: {}", selected);
        StringBuilder block = new StringBuilder(
                "\n\n[이 판에서 우선 확인할 축 — 사연에 그 구조가 실제로 있을 때만 쓰고, 없으면 그 축은 버려라]");
        for (String key : selected) {
            block.append("\n\n").append(axes.get(key).strip());
        }
        return block.toString();
    }

    // 화면에 나갈 진단 카드 하나 — 1호출이 쓴 문장(headline/reading)에 백엔드가 순위와
    // 등급, 묶음, 근거 상태를 붙인 것. 판독 입력과 최종 저장에 같은 값이 쓰인다.
    public record DiagnosisCard(String key, String label, String group, int rank, String level,
                                String evidenceState, String headline, String reading,
                                List<String> factIds) {
    }

    // baseline packet — 1호출이 확정한 것(확률, 등급, 진단 카드, 총평, 시간효과, 관찰 포인트,
    // 통보자)과 축을 전부 뺀다. 남기는 넷은 모델이 스스로 읽어야 할 재료뿐이다.
    // 진단을 함께 주면 모델이 그 진단을 중심으로 이야기를 짜서, 사연에서 무엇을 발견하는지
    // 볼 수 없다 — 이 모드의 목적이 바로 그 발견력 측정이다.
    private String baselinePacketJson(Assessment saved, ReunionDiagnosis diagnosis) {
        List<ReunionDiagnosis.ReadingFact> facts = diagnosis.readingFacts();
        // userMessages는 여기 싣지 않는다 — packet 뒤에 글 모양으로 따로 붙는다.
        // JSON 문자열로 눌러 넣으면 안 읽히고 글로 두면 인용이 살아나는 게 실측됐다.
        Map<String, Object> out = new LinkedHashMap<>();

        List<Map<String, Object>> factRows = new ArrayList<>();
        int order = 1;
        for (ReunionDiagnosis.ReadingFact fact : facts == null ? List.<ReunionDiagnosis.ReadingFact>of() : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", fact.id());
            row.put("order", order++);
            row.put("actor", fact.actor());
            row.put("kind", fact.kind());
            row.put("fact", fact.fact());
            row.put("quote", fact.quote());
            row.put("timing", fact.timing());
            factRows.add(row);
        }
        out.put("readingFacts", factRows);

        List<Map<String, String>> questions = new ArrayList<>();
        if (diagnosis.directQuestions() != null) {
            int qNo = 1;
            for (String question : diagnosis.directQuestions()) {
                questions.add(Map.of("id", String.format("Q%02d", qNo++), "question", question));
            }
        }
        out.put("directQuestions", questions);

        List<Map<String, String>> interpretations = new ArrayList<>();
        if (diagnosis.userFocus() != null) {
            int uNo = 1;
            for (ReunionDiagnosis.FocusItem item : diagnosis.userFocus()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.format("U%02d", uNo++));
                if (item.factId() != null) {
                    row.put("factId", item.factId());
                }
                row.put("interpretation", item.interpretation());
                interpretations.add(row);
            }
        }
        out.put("userInterpretations", interpretations);

        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new LlmException();
        }
    }

    private String packetJson(Assessment saved, ReunionDiagnosis diagnosis, String intakeBlock,
                              String level, List<DiagnosisCard> cards) {
        List<ReunionDiagnosis.ReadingFact> facts = diagnosis.readingFacts();
        if (facts == null || facts.isEmpty()) {
            // 루브릭이 관찰 사실을 아직 안 내는 동안의 안전망 — 요인 근거를 사실로 승격한다.
            facts = fallbackFacts(saved);
        }

        // 재료 병목 추적용 — 내용은 개인정보라 남기지 않고 개수와 형태만 남긴다.
        long withTiming = facts.stream().filter(f -> f.timing() != null && !f.timing().isBlank()).count();
        long withQuote = facts.stream().filter(f -> f.quote() != null && !f.quote().isBlank()).count();
        log.info("판독 재료 storyId={} facts={} (timing={}, quote={})",
                saved.getStoryId(), facts.size(), withTiming, withQuote);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("probability", saved.getProbability());
        out.put("level", level);
        // 누가 이별을 통보했는가. 이게 빠지면 판독이 관찰 사실에서 역할을 역추정하다
        // 두 사람을 뒤집는다(실측: 유저가 통보한 판을 "상대가 이별을 결심한 이유"로 씀).
        String declaredBy = declaredBy(saved, diagnosis);
        if (declaredBy != null) {
            out.put("breakupDeclaredBy", declaredBy);
        }
        if (intakeBlock != null && !intakeBlock.isBlank()) {
            out.put("intake", intakeBlock);
        }
        if (diagnosis.displayDiagnosis() != null) {
            out.put("diagnosisSummary", diagnosis.displayDiagnosis().summary());
        }
        if (diagnosis.timeEffect() != null) {
            ReunionDiagnosis.TimeEffect effect = diagnosis.timeEffect();
            Map<String, Object> time = new LinkedHashMap<>();
            time.put("state", effect.state());
            time.put("horizon", effect.horizon());
            time.put("actionBias", effect.actionBias());
            time.put("reason", effect.reason());
            out.put("timeEffect", time);
        }
        // 관계심리 재료(1호출 해석 문장)는 싣지 않는다 — 두 번째 퇴출. 설명 문장만 골라
        // 되살렸더니 판독이 원문 대신 이 해석을 받아 확대하는 게 재실측됐다(요구-철회
        // 문장이 순환 서술로, needConflict 단어가 사연에 없는 어휘로 복창됨). 판독은
        // userMessages 원문에서 심리를 직접 읽고, 개념은 사전(psychology 키)이 공급한다.

        List<Map<String, Object>> cardRows = new ArrayList<>();
        for (DiagnosisCard card : cards) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", card.key());
            row.put("label", card.label());
            row.put("group", card.group());
            row.put("rank", card.rank());
            row.put("level", card.level());
            row.put("evidenceState", card.evidenceState());
            row.put("headline", card.headline());
            row.put("reading", card.reading());
            row.put("factIds", card.factIds());
            cardRows.add(row);
        }
        out.put("diagnosisItems", cardRows);

        // 유저 원문은 packet(JSON) 밖으로 옮겼다 — read()에서 coda 앞에 글 모양으로 붙는다.
        // JSON 문자열로 눌린 원문은 규칙 더미에 묻혀 안 읽히는 게 실측됐고, 이 자리에서는
        // 상담자 발화 제외 원칙만 유지한다(해석 오염 방지 — 골든셋 12와 같은 원칙).

        List<Map<String, Object>> factRows = new ArrayList<>();
        int order = 1;
        for (ReunionDiagnosis.ReadingFact fact : facts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", fact.id());
            row.put("order", order++);
            row.put("actor", fact.actor());
            row.put("kind", fact.kind());
            row.put("fact", fact.fact());
            if (fact.quote() != null) {
                row.put("quote", fact.quote());
            }
            if (fact.timing() != null) {
                row.put("timing", fact.timing());
            }
            factRows.add(row);
        }
        out.put("readingFacts", factRows);

        List<Map<String, String>> questions = new ArrayList<>();
        if (diagnosis.directQuestions() != null) {
            int qNo = 1;
            for (String question : diagnosis.directQuestions()) {
                questions.add(Map.of("id", String.format("Q%02d", qNo++), "question", question));
            }
        }
        out.put("directQuestions", questions);

        List<Map<String, String>> interpretations = new ArrayList<>();
        if (diagnosis.userFocus() != null) {
            int uNo = 1;
            for (ReunionDiagnosis.FocusItem item : diagnosis.userFocus()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("id", String.format("U%02d", uNo++));
                if (item.factId() != null) {
                    row.put("factId", item.factId());
                }
                row.put("interpretation", item.interpretation());
                interpretations.add(row);
            }
        }
        out.put("userInterpretations", interpretations);

        List<String> watch = new ArrayList<>();
        saved.getWatchPoints().forEach(w -> watch.add(w.getPoint() + " — " + w.getEffect()));
        out.put("watchFor", watch);

        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            // 직렬화 실패는 코드 결함 — 판독 없이 진행하게 위로 던진다(판정은 이미 저장됨).
            throw new LlmException();
        }
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    // 통보자는 매칭 분류(1호출)가 이미 가려둔 값이다. 없으면 점프 규칙에서 읽는다 —
    // 유저 통보 계열 점프는 이름 자체가 "유저가 통보했다"를 전제로 발동한다.
    private String declaredBy(Assessment saved, ReunionDiagnosis diagnosis) {
        if (diagnosis.matchProfile() != null && diagnosis.matchProfile().dumper() != null
                && !"미상".equals(diagnosis.matchProfile().dumper())) {
            return diagnosis.matchProfile().dumper();
        }
        JumpRule jump = saved.getJumpRule();
        if (jump != null && jump.label().startsWith("유저통보")) {
            return "나";
        }
        return null;
    }

    private List<ReunionDiagnosis.ReadingFact> fallbackFacts(Assessment saved) {
        List<ReunionDiagnosis.ReadingFact> out = new ArrayList<>();
        for (AssessmentFactor factor : saved.getFactors()) {
            String evidence = factor.getEvidence();
            if (evidence == null || evidence.isBlank() || ReunionLlm.NO_EVIDENCE.equals(evidence)) {
                continue;
            }
            out.add(new ReunionDiagnosis.ReadingFact(
                    String.format("F%02d", out.size() + 1), "CONTEXT", "ACTION",
                    evidence, null, null));
        }
        return out;
    }

    // v5 계약. 칸을 넷으로 줄였다 — eyebrow/title/answer로 나눠 두면 한 결론을 세 번 쓰게 되고,
    // 그 중복은 지시로 못 막는다(스키마가 요구하기 때문). psychology 슬롯도 없앴다: 사전 주입을
    // 끊었는데도 슬롯이 남아 41장 중 21장에 개념이 붙었다(실측). 필요하면 reading 안에서 쓴다.
    private static Map<String, Object> chapterSchema() {
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("question", Map.of("type", "STRING")),
                        Map.entry("verdict", Map.of("type", "STRING")),
                        Map.entry("reading", Map.of("type", "STRING")),
                        Map.entry("role", Map.of("type", "STRING",
                                "enum", ReadingVocab.CHAPTER_ROLES)),
                        // 주장 한 줄. 이 칸이 있어야 모델이 장을 쓰기 전에 "내가 무엇을
                        // 주장하는가"를 먼저 확정하고, 그 확정본끼리 겹침을 볼 수 있다.
                        Map.entry("claimSignature", Map.of("type", "STRING")),
                        Map.entry("interpretationId", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("evidenceIds", Map.of("type", "ARRAY",
                                "items", Map.of("type", "STRING"))))),
                Map.entry("required", List.of("question", "verdict", "reading", "role",
                        "claimSignature")),
                Map.entry("propertyOrdering", List.of("claimSignature", "question", "verdict",
                        "reading", "role", "interpretationId", "evidenceIds")));
    }

    // 02의 끝점. 세 축을 한 문장으로 뭉치면 "좋아한다/아니다"로 납작해져서 따로 받는다.
    private static Map<String, Object> currentStateSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "feeling", Map.of("type", "STRING"),
                        "choice", Map.of("type", "STRING"),
                        "repairBelief", Map.of("type", "STRING")),
                "required", List.of("feeling", "choice", "repairBelief"),
                "propertyOrdering", List.of("feeling", "choice", "repairBelief"));
    }

    // strength에는 enum을 걸지 않는다 — 비활성일 땐 null이라 nullable과 enum이 함께 걸린
    // 필드가 되고, 그 조합은 프로바이더마다 해석이 갈린다. 값 검증은 파서가 한다.
    private static Map<String, Object> delayedRegretSchema() {
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("active", Map.of("type", "BOOLEAN")),
                        Map.entry("strength", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("headline", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("whyNotNow", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("whyLater", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("basis", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("limit", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("evidenceIds", Map.of("type", "ARRAY",
                                "items", Map.of("type", "STRING"))))),
                Map.entry("required", List.of("active")),
                Map.entry("propertyOrdering", List.of("active", "strength", "headline", "whyNotNow",
                        "whyLater", "basis", "limit", "evidenceIds")));
    }

    // 진단 배열은 스키마에 두되 내용은 안 믿는다 — 서버가 입력값으로 덮는다.
    // 그래도 요구하는 이유: 모델이 진단을 읽고 쓰는 절차를 밟아야 심층 장이 진단과
    // 어긋나지 않는다(읽지도 않고 쓰면 같은 리포트가 두 판을 말한다).
    private static Map<String, Object> diagnosisEchoSchema() {
        return Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "key", Map.of("type", "STRING", "enum", ReadingVocab.DIAGNOSIS_KEYS),
                        "label", Map.of("type", "STRING"),
                        "group", Map.of("type", "STRING", "enum", ReadingVocab.GROUPS),
                        "rank", Map.of("type", "INTEGER"),
                        "level", Map.of("type", "STRING", "enum", ReadingVocab.LEVELS),
                        "evidenceState", Map.of("type", "STRING",
                                "enum", ReadingVocab.EVIDENCE_STATES),
                        "headline", Map.of("type", "STRING"),
                        "reading", Map.of("type", "STRING"),
                        "factIds", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("key", "headline", "reading"),
                "propertyOrdering", List.of("key", "label", "group", "rank", "level",
                        "evidenceState", "headline", "reading", "factIds"));
    }

    // nextMove만 required에서 뺀다 — STOP_CONTACT일 땐 다음 행동을 만들지 않는 게 지시다.
    private static Map<String, Object> actionPlanSchema() {
        return Map.ofEntries(
                Map.entry("type", "OBJECT"),
                Map.entry("properties", Map.ofEntries(
                        Map.entry("title", Map.of("type", "STRING")),
                        Map.entry("stance", Map.of("type", "STRING", "enum", ReadingVocab.STANCES)),
                        Map.entry("answer", Map.of("type", "STRING")),
                        Map.entry("timing", Map.of("type", "STRING")),
                        Map.entry("whyThisTiming", Map.of("type", "STRING")),
                        Map.entry("nextMove", Map.of("type", "STRING", "nullable", true)),
                        Map.entry("mindset", Map.of("type", "STRING")),
                        Map.entry("goal", Map.of("type", "STRING")),
                        Map.entry("decisionValue", Map.of("type", "STRING")),
                        Map.entry("do", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                        Map.entry("stopCondition", Map.of("type", "STRING")),
                        Map.entry("ifClosed", Map.of("type", "STRING")),
                        Map.entry("avoid", Map.of("type", "ARRAY",
                                "items", Map.of("type", "STRING"))))),
                Map.entry("required", List.of("title", "stance", "answer", "timing",
                        "whyThisTiming", "mindset", "goal", "decisionValue", "do", "stopCondition",
                        "ifClosed", "avoid")),
                Map.entry("propertyOrdering", List.of("title", "stance", "answer", "timing",
                        "whyThisTiming", "nextMove", "mindset", "goal", "decisionValue", "do",
                        "stopCondition", "ifClosed", "avoid")));
    }

    // 판독 응답의 문법(v5). reading.yml의 출력 형식과 짝 — 지시를 고쳐 필드가 바뀌면 여기도 같이.
    // 스키마가 프롬프트를 이긴다(구조화 출력): 지시만 고치고 여기를 안 고치면 새 필드는
    // 아예 생성되지 못하고 옛 모양으로 나온다(실측 — v13 지시가 v12 스키마에 갇혔다).
    // internal 4개 상태는 폐지 — 화면에도 코드에도 안 쓰이면서 매번 판정만 시켰다.
    // 그 자리를 currentState가 대신한다: 같은 종합을 유저가 읽는 말로 내보낸다.
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    Map.entry("diagnosisSummary", Map.of("type", "STRING")),
                    Map.entry("diagnosis", Map.of("type", "ARRAY", "items", diagnosisEchoSchema())),
                    Map.entry("analysisSection", Map.of(
                            "type", "OBJECT",
                            "properties", Map.of("title", Map.of("type", "STRING")),
                            "required", List.of("title"),
                            "propertyOrdering", List.of("title"))),
                    Map.entry("analysisChapters", Map.of("type", "ARRAY", "items", chapterSchema())),
                    Map.entry("currentState", currentStateSchema()),
                    Map.entry("delayedRegretSignal", delayedRegretSchema()),
                    Map.entry("actionPlan", actionPlanSchema()))),
            Map.entry("required", List.of("diagnosisSummary", "diagnosis", "analysisSection",
                    "analysisChapters", "currentState", "delayedRegretSignal", "actionPlan")),
            Map.entry("propertyOrdering", List.of("diagnosisSummary", "diagnosis",
                    "analysisSection", "analysisChapters", "currentState", "delayedRegretSignal",
                    "actionPlan")));

    // baseline 스키마 — 칸이 셋뿐이다. 역할 태그도, 질문 칸도, 주장 압축도, 고정 3축도 없다.
    // 칸을 만들면 모델이 그 칸을 채우려 하고, 그러면 측정하려는 게 발견력이 아니라 순응도가 된다.
    // 통합 스키마 — 판독(장, 종합)과 리포트(판, 이유, 마음, 답, 행동)를 한 호출이 쓴다.
    // 편집자(별도 결정 호출)는 통찰 유실과 분산의 근원이라 제거했다. 출력 순서가 곧 사고
    // 순서다: 장 → 종합 → 판 → 이유 → 신호 → 마음 → 목소리 → 답 → 행동.
    private static final Map<String, Object> BASELINE_SCHEMA = Map.ofEntries(
            Map.entry("type", "OBJECT"),
            Map.entry("properties", Map.ofEntries(
                    // DATING은 상태에서 제거 — 만나는 중 사연은 게이트가 INSUFFICIENT로 안내한다.
                    Map.entry("caseStatus", Map.of("type", "STRING",
                            "enum", List.of("POSSIBLE", "INSUFFICIENT", "REUNITED"))),
                    Map.entry("gateNote", Map.of("type", "STRING", "nullable", true)),
                    Map.entry("analysisSection", Map.of(
                            "type", "OBJECT",
                            "properties", Map.of("title", Map.of("type", "STRING")),
                            "required", List.of("title"),
                            "propertyOrdering", List.of("title"))),
                    Map.entry("analysisChapters", Map.of("type", "ARRAY", "items", Map.ofEntries(
                            Map.entry("type", "OBJECT"),
                            Map.entry("properties", Map.of(
                                    "title", Map.of("type", "STRING"),
                                    "reading", Map.of("type", "STRING"),
                                    "evidenceIds", Map.of("type", "ARRAY",
                                            "items", Map.of("type", "STRING")))),
                            Map.entry("required", List.of("title", "reading", "evidenceIds")),
                            Map.entry("propertyOrdering",
                                    List.of("title", "reading", "evidenceIds"))))),
                    Map.entry("synthesis", Map.of("type", "STRING")),
                    Map.entry("outlookLevel", Map.of("type", "STRING")),
                    Map.entry("outlookAnalysis", Map.of("type", "STRING")),
                    Map.entry("reasons", Map.of("type", "ARRAY", "items", Map.ofEntries(
                            Map.entry("type", "OBJECT"),
                            Map.entry("properties", Map.of(
                                    "label", Map.of("type", "STRING"),
                                    "direction", Map.of("type", "STRING",
                                            "enum", List.of("UP", "DOWN")),
                                    "reading", Map.of("type", "STRING"))),
                            Map.entry("required", List.of("label", "direction", "reading")),
                            Map.entry("propertyOrdering",
                                    List.of("label", "direction", "reading"))))),
                    // 1장은 판과 이유 뒤에 생성된다(화면 순서와 반대) — 확정된 판을 향해
                    // 쓰게 해서 서사의 결론이 게이지와 어긋나는 리포트를 구조로 막는다.
                    // hook은 스키마에서 내렸다(2026-08-25) — 견본으로도 결이 안 잡히는 유일한
                    // 부품이라 제거, 첫 블록 소제목이 그 역할을 한다. 파서와 화면은 옛 저장분을
                    // 위해 hook을 계속 받아준다.
                    Map.entry("prologueBlocks", Map.of("type", "ARRAY", "items", Map.ofEntries(
                            Map.entry("type", "OBJECT"),
                            Map.entry("properties", Map.of(
                                    "subtitle", Map.of("type", "STRING"),
                                    "body", Map.of("type", "STRING"))),
                            Map.entry("required", List.of("subtitle", "body")),
                            Map.entry("propertyOrdering", List.of("subtitle", "body"))))),
                    Map.entry("mind", Map.of("type", "STRING")),
                    Map.entry("innerVoice", Map.of("type", "STRING", "nullable", true)),
                    Map.entry("answers", Map.of("type", "ARRAY", "items", Map.ofEntries(
                            Map.entry("type", "OBJECT"),
                            Map.entry("properties", Map.of(
                                    "question", Map.of("type", "STRING"),
                                    "answer", Map.of("type", "STRING"))),
                            Map.entry("required", List.of("question", "answer")),
                            Map.entry("propertyOrdering", List.of("question", "answer"))))),
                    Map.entry("action", Map.ofEntries(
                            Map.entry("type", "OBJECT"),
                            Map.entry("properties", Map.ofEntries(
                                    Map.entry("now", Map.of("type", "STRING")),
                                    Map.entry("why", Map.of("type", "STRING")),
                                    Map.entry("nextMove", Map.of("type", "STRING")),
                                    Map.entry("timing", Map.of("type", "STRING")),
                                    Map.entry("stopCondition", Map.of("type", "STRING")))),
                            Map.entry("required", List.of("now", "why", "nextMove", "timing",
                                    "stopCondition")),
                            Map.entry("propertyOrdering", List.of("now", "why", "nextMove",
                                    "timing", "stopCondition")))))),
            Map.entry("required", List.of("caseStatus", "analysisSection", "analysisChapters",
                    "synthesis", "outlookLevel", "outlookAnalysis", "reasons",
                    "prologueBlocks", "mind", "answers", "action")),
            Map.entry("propertyOrdering", List.of("caseStatus", "gateNote", "analysisSection",
                    "analysisChapters", "synthesis", "outlookLevel", "outlookAnalysis",
                    "reasons", "prologueBlocks",
                    "mind", "innerVoice", "answers", "action")));

    // 심층 장 수는 지시상 상한이 없다(핵심 수만큼, 삭제 테스트가 관문). 여기 10은 내용
    // 규칙이 아니라 응답이 망가져 장을 무한정 쏟아내는 사고를 막는 파서 안전핀이다.
    private static final int CHAPTER_MAX = 10;
    private static final int LIST_TEXT_MAX = 300;

    // baseline 파서 — 장(title/reading/evidenceIds)과 synthesis만 받는다.
    // 진단 카드는 여기서도 1호출 값을 그대로 병합한다(화면 01은 그대로 서야 하니까).
    // 행동 계획은 만들지 않는다. 이 모드는 02의 발견력만 보는 자리다.
    private ReadingDraft parseBaseline(String json, ReunionDiagnosis diagnosis,
                                       List<DiagnosisCard> cards) {
        try {
            // 단일 호출 경로에서는 diagnosis가 없다(1호출 제거) — 표시값은 빈 값으로 간다.
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));
            List<ReadingDraft.Chapter> chapters = new ArrayList<>();
            for (JsonNode node : root.path("analysisChapters")) {
                if (chapters.size() >= CHAPTER_MAX) {
                    break;
                }
                String title = text(node, "title");
                String reading = text(node, "reading");
                if (title.isBlank() || reading.isBlank()) {
                    continue;
                }
                // 옛 화면이 verdict를 제목 자리에 그리므로 title을 그 칸에 싣는다.
                chapters.add(new ReadingDraft.Chapter(null, clip(title, LIST_TEXT_MAX),
                        stripFactIds(reading), "TRAJECTORY", null, null,
                        strings(node.path("evidenceIds"), 10)));
            }
            if (chapters.isEmpty()) {
                log.warn("baseline 판독에 장이 없음 — 판독 실패 처리");
                throw new LlmException();
            }

            // 진단 카드는 판정 기능과 함께 내렸다 — 새 판은 빈 목록이 정상이다(4막은 안 그린다).
            List<ReadingDraft.Diagnosis> merged = new ArrayList<>();
            for (DiagnosisCard card : cards) {
                merged.add(new ReadingDraft.Diagnosis(card.key(), card.label(), card.group(),
                        card.rank(), card.level(), card.evidenceState(), card.headline(),
                        card.reading(), card.factIds()));
            }

            ReunionDiagnosis.DisplayDiagnosis display =
                    diagnosis == null ? null : diagnosis.displayDiagnosis();

            // 리포트 필드 — 같은 호출의 뒷부분. 스키마 순서상 장과 종합을 쓴 다음에 생성된다.
            String level = text(root, "outlookLevel");
            if (!OUTLOOK_SCORES.containsKey(level)) {
                log.warn("outlookLevel이 어휘 밖({}) — MID로 접음", level);
                level = "MID";
            }
            List<ReadingDraft.Decision.Reason> reasons = new ArrayList<>();
            for (JsonNode node : root.path("reasons")) {
                String label = text(node, "label");
                String direction = text(node, "direction");
                // 개수는 지시가 정한다(제한 없음) — 8은 내용 규칙이 아니라 폭주 방지 안전핀.
                if (!label.isBlank() && reasons.size() < 8) {
                    reasons.add(new ReadingDraft.Decision.Reason(
                            clip(label, 40),
                            "UP".equals(direction) ? "UP" : "DOWN",
                            stripFactIds(text(node, "reading"))));
                }
            }
            List<ReadingDraft.Decision.Answer> answers = new ArrayList<>();
            for (JsonNode node : root.path("answers")) {
                String question = text(node, "question");
                String answer = text(node, "answer");
                if (!question.isBlank() && !answer.isBlank()) {
                    answers.add(new ReadingDraft.Decision.Answer(
                            clip(question, LIST_TEXT_MAX), stripFactIds(answer)));
                }
            }
            String mind = stripFactIds(text(root, "mind"));
            if (mind.isBlank()) {
                log.warn("판독에 mind가 없음 — 판독 실패 처리");
                throw new LlmException();
            }
            JsonNode action = root.path("action");
            // 1장 필드는 없어도 판독을 접지 않는다 — 비면 화면이 그 자리를 숨긴다.
            // 통짜 prologue와 공감/현실 2필드는 구세대 자리라 null로 둔다.
            List<ReadingDraft.Decision.PrologueBlock> blocks = new ArrayList<>();
            for (JsonNode node : root.path("prologueBlocks")) {
                String subtitle = text(node, "subtitle");
                String body = stripFactIds(text(node, "body"));
                // 개수 상한은 내용 규칙이 아니라 응답 폭주 방지 안전핀.
                if (!subtitle.isBlank() && !body.isBlank() && blocks.size() < 6) {
                    blocks.add(new ReadingDraft.Decision.PrologueBlock(
                            clip(subtitle, LIST_TEXT_MAX), body));
                }
            }
            ReadingDraft.Decision decision = new ReadingDraft.Decision(
                    stripFactIds(text(root, "hook")), null, null, null, blocks, List.of(),
                    mind, null, stripFactIds(text(root, "innerVoice")),
                    level, stripFactIds(text(root, "outlookAnalysis")),
                    OUTLOOK_SCORES.get(level), List.of(), reasons, answers, null, null);
            ReadingDraft.ActionPlan plan = new ReadingDraft.ActionPlan(
                    "그래서 지금 어떻게 할까",
                    stripFactIds(text(action, "now")),
                    stripFactIds(text(action, "why")),
                    clip(text(action, "timing"), LIST_TEXT_MAX),
                    null,
                    stripFactIds(text(action, "nextMove")),
                    null, null, null, List.of(),
                    stripFactIds(text(action, "stopCondition")),
                    null,
                    List.of());
            log.info("판독 리포트: 장 {}개, outlook={}, 이유 {}개, 답변 {}개",
                    chapters.size(), level, reasons.size(), answers.size());
            return new ReadingDraft(
                    display != null ? display.summary() : "",
                    display != null ? display.timeInsight() : null,
                    merged,
                    clip(text(root.path("analysisSection"), "title"), LIST_TEXT_MAX),
                    chapters,
                    null,
                    stripFactIds(text(root, "synthesis")),
                    null,
                    plan,
                    decision);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            log.error("baseline 판독 JSON 파싱 실패 (본문 길이 {}자)", json == null ? 0 : json.length(), e);
            throw new LlmException();
        }
    }

    // 숫자는 코드가 정한다 — 같은 판정이면 같은 숫자. 모델이 내는 숫자는 재실행마다 흔들린다.
    static final Map<String, Integer> OUTLOOK_SCORES = Map.of(
            "VERY_LOW", 10, "LOW", 25, "MID", 45, "HIGH", 68, "VERY_HIGH", 85);

    private ReadingDraft parse(String json, ReunionDiagnosis diagnosis,
                               List<DiagnosisCard> cards) {
        try {
            JsonNode root = objectMapper.readTree(LlmJson.salvage(json));

            List<ReadingDraft.Chapter> chapters = new ArrayList<>();
            for (JsonNode node : root.path("analysisChapters")) {
                if (chapters.size() >= CHAPTER_MAX) {
                    break;
                }
                String question = text(node, "question");
                String verdict = text(node, "verdict");
                String reading = text(node, "reading");
                if (question.isBlank() || verdict.isBlank() || reading.isBlank()) {
                    continue;
                }
                String role = text(node, "role");
                String interpretationId = text(node, "interpretationId");
                chapters.add(new ReadingDraft.Chapter(
                        clip(question, LIST_TEXT_MAX),
                        clip(verdict, 500),
                        stripFactIds(reading),
                        ReadingVocab.CHAPTER_ROLES.contains(role) ? role : "TRAJECTORY",
                        clip(text(node, "claimSignature"), LIST_TEXT_MAX),
                        interpretationId.isBlank() ? null : clip(interpretationId, 10),
                        strings(node.path("evidenceIds"), 10)));
            }

            // 진단 문장은 1호출 값을 그대로 쓴다 — 모델이 복사하며 고쳐 쓸 여지를 없앤다.
            List<ReadingDraft.Diagnosis> merged = new ArrayList<>();
            for (DiagnosisCard card : cards) {
                merged.add(new ReadingDraft.Diagnosis(card.key(), card.label(), card.group(),
                        card.rank(), card.level(), card.evidenceState(), card.headline(),
                        card.reading(), card.factIds()));
            }
            // 진단이 하나도 없으면 리포트가 아니다 — 판독 실패로 처리한다(판정은 유지).
            if (merged.isEmpty()) {
                log.warn("화면 진단 카드가 없음 — 판독 실패 처리");
                throw new LlmException();
            }

            JsonNode planNode = root.path("actionPlan");
            String stance = text(planNode, "stance");
            ReadingDraft.ActionPlan plan = new ReadingDraft.ActionPlan(
                    clip(requireText(planNode, "title"), LIST_TEXT_MAX),
                    ReadingVocab.STANCES.contains(stance) ? stance : "HOLD_AND_REASSESS",
                    clip(requireText(planNode, "answer"), 500),
                    clip(requireText(planNode, "timing"), LIST_TEXT_MAX),
                    clip(text(planNode, "whyThisTiming"), 500),
                    clip(text(planNode, "nextMove"), LIST_TEXT_MAX),
                    clip(text(planNode, "mindset"), LIST_TEXT_MAX),
                    clip(text(planNode, "goal"), LIST_TEXT_MAX),
                    clip(text(planNode, "decisionValue"), LIST_TEXT_MAX),
                    strings(planNode.path("do"), 4),
                    clip(text(planNode, "stopCondition"), LIST_TEXT_MAX),
                    clip(text(planNode, "ifClosed"), 500),
                    strings(planNode.path("avoid"), 4));

            ReunionDiagnosis.DisplayDiagnosis display = diagnosis.displayDiagnosis();
            return new ReadingDraft(
                    display != null ? display.summary() : "",
                    display != null ? display.timeInsight() : null,
                    merged,
                    clip(text(root.path("analysisSection"), "title"), LIST_TEXT_MAX),
                    chapters,
                    currentState(root.path("currentState")),
                    null,
                    delayedRegret(root.path("delayedRegretSignal")),
                    plan,
                    null);
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            // 본문에는 사연 기반 서술이 들어 있어 개인정보다 — 원문 전체는 남기지 않는다.
            log.error("정밀 판독 JSON 파싱 실패 (본문 길이 {}자)", json == null ? 0 : json.length(), e);
            throw new LlmException();
        }
    }

    // 세 축 중 하나라도 비면 종합이 아니다 — 반쪽짜리를 화면에 올리지 않고 통째로 숨긴다.
    private ReadingDraft.CurrentState currentState(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        String feeling = text(node, "feeling");
        String choice = text(node, "choice");
        String repairBelief = text(node, "repairBelief");
        if (feeling.isBlank() || choice.isBlank() || repairBelief.isBlank()) {
            log.warn("현재 상태 종합이 비어 있음 — 표시하지 않음");
            return null;
        }
        return new ReadingDraft.CurrentState(clip(feeling, 500), clip(choice, 500),
                clip(repairBelief, 500));
    }

    // active=false면 통째로 버린다 — 화면에 "해당 없음" 자리를 만들지 않기 위함이고,
    // 활성인데 왜 지금이 아닌지와 왜 나중인지가 비면 근거 없는 희망만 남아 같이 버린다.
    private ReadingDraft.DelayedRegret delayedRegret(JsonNode node) {
        if (!node.isObject() || !node.path("active").asBoolean(false)) {
            return null;
        }
        String whyNotNow = text(node, "whyNotNow");
        String whyLater = text(node, "whyLater");
        String basis = text(node, "basis");
        if (whyNotNow.isBlank() || whyLater.isBlank() || basis.isBlank()) {
            log.warn("뒤늦은 후회 마크가 활성인데 근거가 비어 있음 — 표시하지 않음");
            return null;
        }
        String strength = text(node, "strength");
        String headline = text(node, "headline");
        String limit = text(node, "limit");
        return new ReadingDraft.DelayedRegret(
                ReadingVocab.REGRET_STRENGTHS.contains(strength) ? strength : "MODERATE",
                headline.isBlank() ? null : clip(headline, LIST_TEXT_MAX),
                clip(whyNotNow, 500), clip(whyLater, 500), clip(basis, 500),
                limit.isBlank() ? null : clip(limit, LIST_TEXT_MAX),
                strings(node.path("evidenceIds"), 10));
    }

    // 스키마가 enum을 강제하지만 salvage 경로(잘린 응답 복구)는 뚫릴 수 있어 한 번 더 거른다.
    private String state(JsonNode node, String field, List<String> allowed, String fallback) {
        String value = text(node, field);
        if (allowed.contains(value)) {
            return value;
        }
        log.warn("판독 state 폐기(사전에 없음): {}={} — {}로 대체", field, value, fallback);
        return fallback;
    }

    private List<String> strings(JsonNode array, int max) {
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String value = node.isNull() ? "" : node.asText("").trim();
            if (value.isBlank() || out.size() >= max) {
                continue;
            }
            out.add(clip(value, LIST_TEXT_MAX));
        }
        return out;
    }

    // 리포트의 뼈대가 비면 성립하지 않는다 — 판독만 실패시킨다(판정은 유지).
    private String requireText(JsonNode node, String field) {
        String value = text(node, field);
        if (value.isEmpty()) {
            log.warn("정밀 판독 필수 필드 누락: {}", field);
            throw new LlmException();
        }
        return value;
    }

    // null은 미기입으로 본다. Jackson은 NullNode.asText()를 문자열 "null"로 주기 때문에
    // 그냥 읽으면 화면에 "null"이 찍힌다 — 선택 필드를 null 허용으로 받는 경로에서 실제로 온다.
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() ? "" : value.asText("").trim();
    }

    private String clip(String value, int max) {
        if (value == null) {
            return "";
        }
        String cleaned = stripFactIds(value);
        return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
    }

    // 본문에 새는 근거 번호("(F03)", "(F01, F05)") 제거 — 지시로 금지해도 새는 게 실측돼
    // 파서가 물리적으로 걷어낸다. 유저 화면에 내부 번호가 보이면 리포트가 검사지가 된다.
    private static final java.util.regex.Pattern INLINE_FACT_IDS =
            java.util.regex.Pattern.compile("\\s*\\(F\\d{1,3}(?:\\s*,\\s*F\\d{1,3})*\\)");

    private String stripFactIds(String value) {
        // 가운뎃점은 서비스 전면 금지 어휘인데 모델 출력으로 샌다(실측: "위로·연락·개인 시간").
        // 지시 대신 여기서 치환한다 — 결정적이고 재생성 비용이 없다. clip도 이 경로를 지난다.
        return INLINE_FACT_IDS.matcher(value).replaceAll("").replace("·", ", ");
    }
}
