package com.threeam.match.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

// 고른 사례 목록을 TEXT 컬럼에 JSON 통짜로 넣고 꺼낸다(관계 심리와 같은 문법).
// 항목별 컬럼을 파지 않는 이유: 표시 전용이라 쿼리할 일이 없고, 해설 필드가 늘거나 줄 때
// DDL이 따라붙는 걸 피한다.
//
// 읽기 실패는 빈 목록으로 삼킨다 — 옛 구조로 저장된 행 하나 때문에 조회가 500이 되면
// 유료로 산 결과를 못 보는 게 되고, 그건 파싱 실패보다 나쁜 사고다.
@Slf4j
@Converter
public class MatchPicksConverter implements AttributeConverter<MatchPick.Picks, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(MatchPick.Picks attribute) {
        try {
            return MAPPER.writeValueAsString(attribute == null ? empty() : attribute);
        } catch (Exception e) {
            // 여기서 null을 돌려주면 NOT NULL 컬럼에 걸려 저장 자체가 터진다 — 빈 목록으로 남긴다.
            log.error("매칭 선택 직렬화 실패 — 빈 목록으로 저장됨", e);
            return "{\"items\":[]}";
        }
    }

    private MatchPick.Picks empty() {
        return new MatchPick.Picks(null, null);
    }

    @Override
    public MatchPick.Picks convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return empty();
        }
        try {
            return MAPPER.readValue(dbData, MatchPick.Picks.class);
        } catch (Exception e) {
            log.error("매칭 선택 역직렬화 실패 — 사례 없이 표시됨", e);
            return empty();
        }
    }
}
