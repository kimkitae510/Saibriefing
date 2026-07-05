package com.threeam.assessment.dto;

import com.threeam.assessment.dto.ReunionDiagnosis.TimeInsight;
import java.util.List;

// 정밀 판독의 산출물 — v11.4 계약(지시 전문은 로컬 reading.yml, 출력 형식과 1:1).
// 진단 문장(summary, diagnosis, timeInsight)은 판독이 쓰지 않는다: 1호출이 확정한 것을
// 서버가 그대로 병합한다(2호출이 다시 해석하거나 고쳐 쓸 기회를 없앤다).
// 판독이 실제로 쓰는 것은 심층 장(analysisChapters)과 뒤늦은 후회 마크, 행동 계획, 후속 칩이다.
// 이 객체가 그대로 JSON 직렬화되어 assessment_reading.body에 저장되고 화면으로 내려간다.
public record ReadingDraft(
        String diagnosisSummary,
        TimeInsight timeInsight,          // 시간이 판에 어떻게 작용하는지의 보조 진단. 없으면 null
        List<Diagnosis> diagnosis,
        String analysisSectionTitle,      // 심층 장 묶음의 큰 질문(프론트 섹션 제목)
        List<Chapter> analysisChapters,
        CurrentState currentState,        // 02의 마지막 종합. 세 축을 섞지 않고 따로 세운다
        String synthesis,                 // baseline의 종합. 축을 미리 정하지 않고 한 덩이로 쓴다
        DelayedRegret delayedRegret,      // 근거가 있을 때만. 없으면 null이라 화면이 통째로 숨긴다
        ActionPlan actionPlan,            // 결정 호출이 채운다 — 판독(이해)과 행동을 한 모델에 섞지 않는다
        Decision decision) {              // 결정 호출의 산출 — 분석이 판을 만든다(진단이 아니라)

    // 판을 향한 결론 묶음. 판독이 끝난 뒤 편집자 호출이 확정한다 — 진단을 먼저 박고 그 결론을
    // 설명하게 만들던 순서를 뒤집은 자리다. outlookScore는 LLM이 아니라 코드가 level에서
    // 계산한다: 같은 판정이면 같은 숫자여야 하고, 모델이 내는 숫자는 재실행마다 흔들린다.
    // mind는 1막 본문(지금 상대는 어떤 마음일까), reasons는 판을 만든 동적 이유 2~4개 —
    // 고정 요인 이름 없이 사례가 이유의 제목을 정한다(점수표로 돌아가지 않기 위함).
    // innerVoice는 판독된 마음의 1인칭 번역(선택) — 새 숨은 생각을 추가하지 않는 조건으로만 생성된다.
    // outlookAnalysis는 "왜 이 판인가"를 하나의 흐름으로 설명하는 2~4문장 — reasons의 나열과 다른 층.
    // hook은 1장을 여는 첫 문장. 1장 본문은 소제목 블록 2~4개(prologueBlocks) — 소제목이
    // 그 블록의 결론 문장 역할을 해서 훑어도 논지가 읽힌다. 생성은 판과 이유가 확정된
    // 뒤다(서문을 본문 뒤에 쓰는 원리). prologue(통짜)와 empathy/reality(2필드)는 블록
    // 구조 이전 저장분의 자리 — 새 생성은 채우지 않지만 옛 리포트 표시를 위해 남긴다.
    // mind(통짜 문자열)는 블록 구조 이전 저장분의 자리 — 새 생성은 mindBlocks만 채운다.
    // 한 문자열 필드는 편집이 모든 내용을 한 문단에 밀어 넣게 만든다(실측) — 분석 장과
    // 같은 소제목 블록으로 쪼갠다.
    // verdictBlocks는 재회 가능성 논증 카드 — 소제목이 판정 결론 문장(충동이었지 마음이
    // 떠난 게 아니다 류), 본문이 그 판정이 가능성을 왜 움직이는지, direction이 올림(UP)/
    // 내림(DOWN). 라벨+한 줄 카드(reasons)가 논증을 명패로 해체해 얕아지던 문제의
    // 교체품이다. reasons는 카드 세대 저장분 표시용으로 남긴다.
    // readingBlocks는 소견서 판형(2026-09-07)의 1장 본문 — 분석 문단 전부를 원문 순서대로,
    // 판을 가른 문단에만 direction(UP/DOWN/NONE)이 붙는다. 카드를 오려내지 않고 제자리에
    // 표시만 하려는 자리라 prologueBlocks(카드 뺀 나머지)와 verdictBlocks(카드)를 대신한다.
    // 옛 저장분은 null이라 화면이 이전 판형으로 그린다.
    public record Decision(String hook, String prologue, String prologueEmpathy,
                           String prologueReality, List<PrologueBlock> prologueBlocks,
                           List<PrologueBlock> actionBlocks,
                           String mind, List<PrologueBlock> mindBlocks, String innerVoice,
                           String outlookLevel, String outlookAnalysis, int outlookScore,
                           List<VerdictCard> verdictBlocks,
                           List<Reason> reasons, List<Answer> answers,
                           List<ReadingBlock> readingBlocks, Summary summary) {

        // 게이지 아래 정리표(2026-09-08) — 분류 호출(luna)이 완성된 분석에서 뽑는다. name은 이
        // 이별을 부르는 한 줄, up/down은 가능성을 올리고 내리는 것의 키워드, why는 지금 이
        // 등급인 이유 한 문장. 분석 호출은 이걸 모른다(재회 정리를 향해 쓰이지 않게). 옛 저장분은 null.
        // types는 이 사연에 겹친 이별의 유형 한 줄씩(76판 [유형 비교]). 옛 저장분은 null.
        public record Summary(String name, List<Item> up, List<Item> down, String why, List<String> types) {
            public Summary(String name, List<Item> up, List<Item> down, String why) {
                this(name, up, down, why, List.of());
            }

            // 키워드와 그 판단이 들어 있는 문단 번호(1-based, 분류 호출의 번호). 번호가 잡히면
            // 그 문단의 ReadingBlock.keyword에 같은 키워드가 붙어 카드와 본문이 서로를 가리킨다.
            // note는 그 이름이 왜 올리는지 또는 내리는지 한 줄 — 카드만 읽어도 무엇이 왜인지 알게.
            public record Item(String keyword, Integer para, String note) {
            }
        }

        // pivot은 등급을 실제로 만든 결정적 판단 표시 — 1단 원석의 (가능성: ..., 핵심)
        // 표시를 서버가 파싱해 박는다. 옛 저장분은 null.
        public record VerdictCard(String subtitle, String body, String direction, Boolean pivot) {
        }

        // 소견서 문단 하나. subtitle은 그 문단이 대목의 첫 문단일 때만 sol의 소제목,
        // direction은 판을 가른 문단에만(UP/DOWN/NONE, 없으면 null), pivot은 그 문단이
        // 등급을 만든 대목(sol이 소제목에 (핵심)을 붙인 대목)에 속하는지.
        // keyword는 정리표의 올리는 것/내리는 것 중 이 문단에서 온 것 — 화면이 방향 문구 대신
        // 이 말을 라벨로 쓰고, 위 카드가 이 문단을 가리킨다. 없으면 null.
        public record ReadingBlock(String subtitle, String body, String direction, Boolean pivot,
                                   String keyword) {
        }

        // 소제목 + 본문 블록 — 1장(prologueBlocks)과 행동 장(actionBlocks)이 같은 꼴을 쓴다.
        // 2단 편집이 1단 산문을 배분한 결과라 고정 필드(ActionPlan)보다 자유 블록이 맞다.
        public record PrologueBlock(String subtitle, String body) {
        }
        // label은 그 판단이 다루는 축의 짧은 이름(사례가 정함), direction은 UP(올림)/DOWN(내림).
        // 화면이 방향별로 묶어 "낮게 본 이유 / 그래도 매우 낮음은 아닌 이유"처럼 그린다.
        public record Reason(String label, String direction, String reading) {
        }

        public record Answer(String question, String answer) {
        }
    }

    // group은 핵심/조건부/추가, level은 요인 판정과 같은 어휘(매우유리~매우불리),
    // evidenceState는 확인됨/부재 확인됨/부분 — 화면이 "아직 모르는 것"을 구분해 그린다.
    public record Diagnosis(String key, String label, String group, int rank, String level,
                            String evidenceState, String headline, String reading,
                            List<String> factIds) {
    }

    // 장 하나가 질문 하나를 맡는다. question은 사연자가 속으로 묻는 말, verdict는 그 답,
    // reading은 근거와 해석. 한 결론을 여러 칸에 나눠 담던 옛 구조(eyebrow/title/answer)를
    // 두 칸으로 합쳤다 — 칸이 많으면 스키마가 중복을 요구하게 되고 지시로는 못 막는다.
    // 처방(복구 조건)은 넣지 않는다: 이해할 자리에서 숙제를 받으면 행동 계획과 겹친다.
    // claimSignature는 그 장이 세운 주장을 한 줄로 압축한 내부 값이다. 화면에 안 나간다.
    // 겹치는 것은 태그가 아니라 주장이라 role 중복 검사로는 못 잡는다 — 질문 셋이 달라도
    // 주장이 하나면 같은 말을 세 번 하는 리포트가 된다(실측).
    public record Chapter(String question, String verdict, String reading, String role,
                          String claimSignature, String interpretationId,
                          List<String> evidenceIds) {
    }

    // 02의 끝점. 남은 감정, 지금의 관계 선택, 다시 해도 달라질 거라는 기대는 서로 다른 층이라
    // 한 문장으로 뭉치면 "좋아한다/아니다"로 납작해진다. 세 축을 따로 세워 화면에 내보낸다.
    public record CurrentState(String feeling, String choice, String repairBelief) {
    }

    // 지금은 어렵지만 시간이 지난 뒤 상실감이 커질 사례 고유 근거가 있을 때만 채워진다.
    // 모든 낮은 확률에 붙이는 위로가 아니라서, 근거가 없으면 객체 자체가 없다.
    public record DelayedRegret(String strength, String headline, String whyNotNow, String whyLater,
                                String basis, String limit, List<String> evidenceIds) {
    }

    // 지금 어떻게 움직일지. 시점(timing)과 그 이유, 멈출 조건까지 한 덩이로 준다 —
    // 행동만 있고 멈출 조건이 없으면 유저는 반응이 없을 때 계속 민다.
    // 기다림을 권할 땐 nextMove와 ifClosed가 짝이다: 기다림이 끝나는 지점의 행동 하나와,
    // 그 행동까지 닫혔을 때 왜 다음 재접촉 날짜를 만들지 않는지가 없으면 무기한 대기가 된다.
    public record ActionPlan(String title, String stance, String answer, String timing,
                             String whyThisTiming, String nextMove, String mindset, String goal,
                             String decisionValue, List<String> doList, String stopCondition,
                             String ifClosed, List<String> avoid) {
    }
}
