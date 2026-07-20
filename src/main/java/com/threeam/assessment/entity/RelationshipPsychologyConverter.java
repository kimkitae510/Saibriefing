package com.threeam.assessment.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.dto.RelationshipPsychology;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

// 관계 심리를 TEXT 컬럼에 JSON 통짜로 넣고 꺼낸다. 컬럼을 항목별로 파지 않는 이유:
// 표시 전용이라 쿼리할 일이 없고, 구조가 초기에 바뀔 텐데 그때마다 DDL이 따라붙는 걸 피한다.
// 읽기 실패는 null로 삼킨다 — 구조가 바뀐 옛 행 때문에 진단 조회 전체가 500이 되면 안 된다.
@Slf4j
@Converter
public class RelationshipPsychologyConverter
        implements AttributeConverter<RelationshipPsychology, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(RelationshipPsychology attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            log.error("관계 심리 직렬화 실패 — 이 진단은 관계 심리 없이 저장됨", e);
            return null;
        }
    }

    @Override
    public RelationshipPsychology convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(dbData, RelationshipPsychology.class);
        } catch (Exception e) {
            log.error("관계 심리 역직렬화 실패 — 이 진단은 관계 심리 없이 표시됨", e);
            return null;
        }
    }
}
