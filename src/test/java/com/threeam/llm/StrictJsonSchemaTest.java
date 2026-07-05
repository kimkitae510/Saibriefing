package com.threeam.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StrictJsonSchemaTest {

    @Test
    @DisplayName("Google 스키마를 JSON Schema로 변환 — 타입 소문자, ordering 제거, object에 additionalProperties")
    void convertsGoogleSchema() {
        Map<String, Object> google = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "title", Map.of("type", "STRING"),
                        "rank", Map.of("type", "INTEGER"),
                        "tags", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("title"),
                "propertyOrdering", List.of("title", "rank", "tags"));

        Map<String, Object> out = StrictJsonSchema.fromGoogle(google);

        assertThat(out.get("type")).isEqualTo("object");
        assertThat(out.get("additionalProperties")).isEqualTo(false);
        assertThat(out).doesNotContainKey("propertyOrdering");
        Map<?, ?> props = (Map<?, ?>) out.get("properties");
        assertThat(((Map<?, ?>) props.get("title")).get("type")).isEqualTo("string");
    }

    // 선택 속성이 남아 있으면 Anthropic이 문법 크기 초과(400)를 낸다 — 전부 필수로 올리고
    // 원래 선택이던 것만 null 허용으로 바꾼다.
    @Test
    @DisplayName("선택 속성은 사라진다 — 전부 required로 올리고 null 허용으로 바꿈")
    void liftsOptionalPropertiesToRequiredNullable() {
        Map<String, Object> google = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "title", Map.of("type", "STRING"),
                        "tags", Map.of("type", "ARRAY", "items", Map.of("type", "STRING"))),
                "required", List.of("title"));

        Map<String, Object> out = StrictJsonSchema.fromGoogle(google);

        assertThat((List<Object>) out.get("required")).containsExactlyInAnyOrder("title", "tags");
        Map<?, ?> props = (Map<?, ?>) out.get("properties");
        // 원래 필수였던 title은 그대로, 선택이던 tags만 null이 붙는다
        assertThat(((Map<?, ?>) props.get("title")).get("type")).isEqualTo("string");
        List<?> tags = (List<?>) ((Map<?, ?>) props.get("tags")).get("anyOf");
        assertThat(((Map<?, ?>) tags.get(0)).get("type")).isEqualTo("array");
        assertThat(((Map<?, ?>) tags.get(1)).get("type")).isEqualTo("null");
    }

    @Test
    @DisplayName("nullable:true는 anyOf [본타입, null]로 — enum과 required는 보존")
    void convertsNullableAndKeepsEnum() {
        Map<String, Object> google = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "eyebrow", Map.of("type", "STRING", "nullable", true),
                        "stance", Map.of("type", "STRING", "enum", List.of("A", "B"))),
                "required", List.of("stance"));

        Map<String, Object> out = StrictJsonSchema.fromGoogle(google);

        Map<?, ?> props = (Map<?, ?>) out.get("properties");
        Map<?, ?> eyebrow = (Map<?, ?>) props.get("eyebrow");
        assertThat(eyebrow.containsKey("anyOf")).isTrue();
        List<?> anyOf = (List<?>) eyebrow.get("anyOf");
        assertThat(((Map<?, ?>) anyOf.get(0)).get("type")).isEqualTo("string");
        assertThat(((Map<?, ?>) anyOf.get(1)).get("type")).isEqualTo("null");
        // nullable과 선택이 겹쳐도 anyOf는 한 겹이다(이중 포장 금지)
        assertThat(anyOf).hasSize(2);
        assertThat(((Map<?, ?>) props.get("stance")).get("enum")).isEqualTo(List.of("A", "B"));
    }
}
