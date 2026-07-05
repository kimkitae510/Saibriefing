package com.threeam.assessment;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 정밀 판독(2호출) 지시 전문. 추론 원칙, 네 질문의 경계, 반대 증거 규칙 등 판독 노하우는
// 서비스 자산이라 저장소에 올리지 않고 로컬 reading.yml(gitignore)로 주입한다. rubric.yml과 같은 방식.
// 여기 기본값은 자리표시자 — 로컬 파일이 없으면 서비스는 뜨지만 판독 품질은 크게 떨어진다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.reading")
public class ReadingProperties {

    // 판독(2호출) 프로바이더 분기. 비우면 기존 deep 경로(Gemini), "anthropic"이면 Claude 실험 경로.
    // 진단(1호출)과 채팅은 이 값과 무관하게 기존 프로바이더를 유지한다.
    private String provider = "";

    private String guide = "너는 확정된 재회 판정 위에 왜 그런지를 서술하는 분석가다.";

    // 모델 자체 판독력 측정 모드. 켜면 지시를 baseline 쌍으로 갈고, packet에서 1호출 확정값
    // (확률, 진단 카드, 시간효과, 관찰 포인트)과 축을 전부 뺀다 — 진단이 붙어 있으면
    // 모델이 그 진단을 중심으로 이야기를 구성해서 "스스로 무엇을 발견하는가"를 못 본다.
    // 축 라이브러리와 psychology는 파일에 그대로 두고 주입만 끊는다(되돌리기 위해).
    private boolean baseline = false;

    private String baselineGuide = "";

    private String baselineCoda = "";

    // 2단 파이프라인 1단(자유 분석)의 시스템 지시 — 서비스와 리포트 장 구성을 설명해
    // 원석이 그 그림을 알고 쓰이게 한다. 비어 있으면 코드의 한 줄(전문가 선언)만 쓴다.
    private String analysisGuide = "";

    // 분석 참고 지식(reading.yml knowledge) — 헌법(analysisGuide) 뒤에 [참고 지식]으로 이어
    // 붙인다. 지시와 지식을 한 몸으로 두면 지식 문장이 문항으로 읽혀 원석이 답안지가 되는
    // 실측(2026-09-07)의 분리. 비어 있으면 헌법만 간다.
    private String knowledge = "";

    // 지식 파일 경로(reading.yml knowledge-file) — 있으면 그 파일 전문을 knowledge 대신 붙인다.
    // 판독심리근거.md 4만 자를 통째로 넣어 보는 실험(2026-09-14)용. 비우면 knowledge만.
    private String knowledgeFile = "";

    // 분석 호출의 유저 차례 물음(reading.yml analysis-ask). 비우면 사연만 보낸다. 기본값은 예전 상수.
    private String analysisAsk = "그 사람 쪽에서 무슨 일이 있었는지 써라.";

    // 분석 호출만 다른 모델로 돌리는 실험 스위치(openai 경로). 비우면 판독 모델(sol). 등급 매퍼와
    // 분류는 그대로 판독 모델이라, 글만 바꿔 보고 나머지 비교가 흔들리지 않는다(2026-09-08).
    private String analysisModel = "";

    // 분류 호출(card-by-sol일 때)과 등급 매퍼의 모델 — 비우면 판독 모델(sol). 분류는 출력이 작아
    // 비싼 모델의 값이 입력 토큰에 다 들어가서, 한 급 싼 모델로도 되는지 보는 스위치(2026-09-09).
    private String cardModel = "";

    private String gradeModel = "";

    // 편집 호출(분석 뒤, 분류 앞) 지시 — sol이 쓴 본문의 문장 꼴만 다듬는다(앞절 없는 부정문, 주어
    // 반복, 규칙 검산 문장). 헌법 열두 판으로도 안 잡힌 "X가 아니라 Y" 습관을 프롬프트 밖에서 잡는
    // 자리(2026-09-10). 문단 수와 소제목이 달라지면 원문을 그대로 쓴다(분류가 문단 번호를 쓴다).
    // 비어 있으면 건너뛴다.
    private String editGuide = "";

    private String editModel = "";

    // 결정 호출(luna, 옛 리포트 필드) 스위치. 지금 화면이 실제로 쓰는 것은 sol 본문과 terra 분류뿐이라
    // 끄면 호출이 둘로 줄고(2026-09-10 사장님 "2호출까지만"), 게이트 상태와 게이지 아래 총평 한 줄과
    // 질문 답변이 빠진다 — 게이트는 옛 헌법으로 판정하던 것이고 총평은 정리표 이유가 대신한다.
    private boolean decisionCall = true;

    // 카드 분류 호출(분석 뒤 순차) 시스템 지시 — 분석은 카드를 모른 채 쓰고, 이 호출이
    // 문단 번호로 라벨과 제목만 붙인다. 한 호출에 쓰기와 분류를 같이 시키면 분석이 카드
    // 재료 생산으로 기운다는 실측(2026-09-06)의 해법. 비어 있으면 건너뛴다(편집자 폴백).
    private String cardGuide = "";

    // 카드 분류(라벨, 제목, 책갈피)를 결정 모델(luna) 대신 판독 모델(sol)로 돌린다. 출력이 작아
    // 비용 차이는 몇 원이라, luna가 책갈피와 제목을 못 하면 켠다(2026-09-08 사장님).
    private boolean cardBySol = false;

    // 상대의 지금 마음과 생각 호출(분석 뒤, 카드와 병렬) 지시 — 사연과 1장 분석을 받아 상대의
    // 자리에서 지금 무엇이 같이 있는지를 쓴다. 등급은 안 보여준다. 산출은 원석 끝에
    // [그 사람의 지금 마음과 생각]으로 보관(화면 자리는 판단 뒤). 비어 있으면 건너뛴다.
    private String mindGuide = "";

    // 개발용 — 켜면 판독 기준일을 오늘이 아니라 사연 작성일로 준다. 골든셋 사연을 몇 주 뒤에 다시
    // 돌리면 모델이 "사연은 8월 24일, 오늘은 9월 9일, 그 사이는 모른다"는 문단을 쓰는데, 실서비스는
    // 사연을 쓴 날 판독하므로 그 문단은 시험 환경이 만든 것이다(2026-09-09). 운영은 false.
    private boolean todayFromStory = false;

    // 판정 호출(분석 뒤 순차) 시스템 지시 — 산문은 자유일 때, 판정은 기준과 시연이 있을 때
    // 좋다는 실측으로 호출을 갈랐다. 비어 있으면 판정 호출을 건너뛴다(구 단일 호출 동작).
    private String verdictGuide = "";

    // 현재화 호출 지시 — 과거의 감정과 관계가 지금 어떤 상태로 남았는지만 재구성한다.
    // 판정이 현재화를 건너뛰지 못하게 독립 산출물로 만들었다(2026-08-29).
    // 비어 있으면 건너뛰고 판정이 사연+분석을 직접 받는다(구 동작).
    private String presentGuide = "";

    // 판독 병렬 가지 수 — 2 이상이고 종합 가이드가 있으면 같은 입력으로 독립 판독을
    // N개 만들어 종합한다. 단일 샘플이 안전한 해석으로 수렴하는 문제의 해법(2026-08-29).
    private int verdictSamples = 1;

    // 종합 호출 지시 — 독립 판독들을 비교해 최종 판독을 새로 쓴다(다수결 금지).
    // 비어 있으면 병렬을 켜지 않는다(단일 판독 폴백).
    private String verdictSynthesisGuide = "";

    // 판독 품질 게이트(심사 호출) 지시 — 얕은 판독(사건 재진술 + 일반론)을 REVISE로
    // 탈락시켜 1회 재작성시킨다. 체크리스트를 생성기가 아니라 심사자에 두는 구조(2026-08-29).
    // 비어 있으면 심사를 건너뛴다.
    private String verdictCriticGuide = "";

    // REVISE 재작성 호출 입력의 마지막 덧붙임 문장.
    private String verdictReviseNote = "";

    // 등급 매퍼(초소형 호출) 지시 — 판독문만 보고 5등급으로 옮긴다. 등급 토큰이 판독 서사를
    // 끌어당기는 문제(실측: 같은 사연 VERY_LOW 이탈 2회)로 판독과 등급을 분리했다.
    // 비어 있으면 매핑을 건너뛴다(편집자 폴백).
    private String gradeGuide = "";

    // 결정 호출(3호출) 지시 — 판독이 끝난 뒤 현재 선택, 재회 판, 장벽, 바뀔 조건, 질문 답변,
    // 행동을 확정한다. 비어 있으면 결정 호출을 건너뛰고 판독만 내보낸다(개발 편의).
    private String decisionGuide = "";

    // 관계심리 개념 사전 — 페르소나에서 이식한 독립 사본(llm.reading.psychology).
    // 판독은 페르소나 파일을 참조하지 않는다. 비어 있으면 guide만으로 동작한다.
    private String psychology = "";

    // 판독 직전 명령 — packet 바로 뒤, 모델이 읽는 마지막 문장. 위치가 곧 무게다:
    // 시스템 지시는 사연보다 멀어서 지고, 이건 사연보다 가까워서 이긴다(사연 추종 교정용).
    private String coda = "";

    // 축 라이브러리 — 판의 구조별 확인 축(질문 목록, 결론 아님). 코드가 진단으로 2~4개를
    // 골라 싣는다. 공용 프롬프트 하나에 전 유형의 축을 섞어 서로 희석되던 문제(진동)의 해법.
    private java.util.Map<String, String> axes = new java.util.HashMap<>();

    // 판독에 실제로 주입되는 지시 전문 — guide 뒤에 개념 사전을 잇는다.
    public String fullGuide() {
        if (baseline && !baselineGuide.isBlank()) {
            return baselineGuide;
        }
        if (psychology == null || psychology.isBlank()) {
            return guide;
        }
        return guide + "\n\n[관계심리 개념 사전]\n\n" + psychology;
    }

    // 생성 직전 명령. baseline이면 baseline 것만 쓴다 — 비어 있으면 아무것도 안 붙는 게
    // 의도다. "비면 옛 coda로 폴백"이던 조건은 미니멀 실험에 옛 지시를 몰래 실었다(발견 즉시 수정).
    public String effectiveCoda() {
        return baseline ? baselineCoda : coda;
    }
}
