package com.threeam.llm;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 탐색 채팅 프롬프트 설정. 실제 문구 전문은 저장소에 올리지 않고 로컬 persona.yml(gitignore)로 주입한다.
// 여기 기본값은 자리표시자 겸, 로컬 파일이 없어도 서비스가 도는 안전값.
// 회차별 지시(turn-1, turn-2, ...)와 규칙 묶음(분석, 행동 상담, 관계심리)은 뺐다 — 회차가 형태를
// 찍어내 대화가 설문이 됐고, 채팅은 이제 판독 전 탐색이라 분석과 처방은 리포트의 일이다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.chat")
public class ChatPersonaProperties {

    // 캐릭터 본체 — 누구인지, 말투, 공감의 방식, 경계. 프롬프트의 맨 앞이자 유일한 고정 블록.
    private String persona = "당신은 이별을 겪은 사람의 곁을 지키는 대화 상대입니다.";

    // 목표 블록(채워진 것, 남은 것) 뒤에 붙는 사용 규칙. 코드는 목표 데이터만 만들고 이 문구가 그 뒤에 온다 —
    // 비면 데이터만 실려 모델이 그걸 어떻게 쓸지 모른 채 대화에 끌어다 쓴다.
    private String goalGuide = "";

    // 목표가 다 찼거나 턴 상한에 닿은 판의 마무리 규칙. goal-guide 대신 실린다.
    private String closing = "";

    // 대화가 아직 없는 방의 첫 말 — 지시가 아니라 그대로 나가는 문장이다. 사연을 읽지 않는 대본이라
    // LLM 없이 저장한다(비용 0, 매번 같음). "{이름님은 }"처럼 중괄호 안 "이름" 뒤에 붙인 글자는 이름이
    // 있을 때만 이름과 함께 남고, 없으면 통째로 빠진다. 비면 persona만으로 LLM이 첫 말을 만든다.
    private String opening = "";

    // 프롬프트 맨 끝에 붙는 출력 직전 점검. 비면 미주입.
    private String finalCheck = "";

    // 판독이 쓰는 기준 파일 경로(재회기준.md 등). 있으면 그 전문이 페르소나 바로 뒤에 실린다 —
    // 무엇이 판을 가르는지 상담자가 알아야 이 사연에서 중요한 것부터 묻는다. 비면 미주입.
    private String knowledgeFile = "";

    // 답의 모양 예시. 지시문이 아니라 상담자가 이미 한 턴(user, assistant)으로 페르소나 바로 뒤에 실린다 —
    // 같은 글을 시스템 지시로 넣으면 모델이 설명서로 읽고 틀만 베꼈다(실측). 비우면 꺼진다.
    private List<Example> examples = new ArrayList<>();

    // 예시 턴 앞에 붙는 한 줄. 아래 대화가 사고를 보여 주는 것이고 모양을 베끼라는 게 아니라는 걸 먼저 말한다. 비우면 미주입.
    private String exampleIntro = "";

    // 예시 턴 뒤에 붙는 경계 한 줄. 예시 사연과 지금 사연이 한 사람의 이야기로 읽히지 않게 한다.
    private String exampleNote = "위 대화는 다른 사연자와 한 거다. 지금 사연자는 아래부터고, 위 사연은 모른다.";

    @Getter
    @Setter
    public static class Example {
        private String user = "";
        private String assistant = "";
        // 왜 이 답이 좋은지. 예시 답 바로 뒤에 system으로 실린다 — 모양만 베끼지 않고 뭘 한 답인지 알게 한다. 비우면 미주입.
        private String why = "";

        public boolean filled() {
            return user != null && !user.isBlank() && assistant != null && !assistant.isBlank();
        }
    }
}
