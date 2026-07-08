package com.threeam.llm;

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

    // 프롬프트 맨 끝에 붙는 출력 직전 점검. 비면 미주입.
    private String finalCheck = "";
}
