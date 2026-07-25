package com.threeam.llm;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 채팅 사실 추출 프롬프트. 실문구는 저장소에 올리지 않고 로컬 extractor.yml(gitignore)로 주입한다.
// 여기 기본값은 자리표시자 겸, 로컬 파일이 없어도 추출이 형식은 맞춰 도는 안전값 —
// 다만 원장 품질을 좌우하는 규칙은 전부 빠진 상태다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.extraction")
public class FactExtractionProperties {

    private String prompt = """
            너는 이별 상담 대화의 기록 담당이다. 위로하거나 대답하지 마라.
            아래 JSON으로만 답하라(다른 텍스트 금지):
            { "newFacts": [ "새로 드러난 사실. 한 줄씩." ],
              "summary": "유저의 감정 흐름과 현재 상태 한두 문장" }
            """;
}
