package com.threeam.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Google(Gemini) responseSchema를 엄격 모드 JSON Schema로 변환한다.
// 판독 스키마의 원본은 ReadingLlm의 Google 형식 하나로 유지하고(스키마가 프롬프트를 이긴다 —
// 두 벌을 손으로 맞추면 반드시 어긋난다), 다른 프로바이더로 보낼 때만 이 변환을 거친다.
// Anthropic structured outputs와 OpenAI strict 모드가 같은 규약을 요구해 둘이 함께 쓴다.
//
// 규칙: 타입 소문자화, propertyOrdering 제거(JSON Schema에 없음), OBJECT에
// additionalProperties:false, nullable:true는 anyOf [본타입, null]로.
// 그리고 모든 속성을 required로 올리고 원래 선택이던 것은 null 허용으로 바꾼다 —
// 두 프로바이더 모두 이 모양을 요구한다. Anthropic은 선택 속성이 있으면 스키마를 문법으로
// 컴파일할 때 경우의 수가 곱해져 "compiled grammar is too large" 400을 내고(실측),
// OpenAI strict 모드는 아예 모든 키가 required여야 한다. 값을 안 쓸 땐 null을 받는다.
public final class StrictJsonSchema {

    private StrictJsonSchema() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromGoogle(Map<String, Object> google) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean nullable = Boolean.TRUE.equals(google.get("nullable"));
        Set<String> required = new LinkedHashSet<>(
                (List<String>) google.getOrDefault("required", List.of()));
        Map<String, Object> properties = null;

        for (Map.Entry<String, Object> entry : google.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            switch (key) {
                case "nullable", "propertyOrdering", "required" -> {
                    // JSON Schema에 없거나(propertyOrdering) 아래에서 다시 만드는 것들
                }
                case "type" -> out.put("type", String.valueOf(value).toLowerCase());
                case "items" -> out.put("items", fromGoogle((Map<String, Object>) value));
                case "properties" -> {
                    properties = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> prop : ((Map<String, Object>) value).entrySet()) {
                        Map<String, Object> converted =
                                fromGoogle((Map<String, Object>) prop.getValue());
                        properties.put(prop.getKey(), required.contains(prop.getKey())
                                ? converted : orNull(converted));
                    }
                    out.put("properties", properties);
                }
                default -> out.put(key, value);
            }
        }

        if ("object".equals(out.get("type"))) {
            out.put("additionalProperties", false);
            out.put("required", properties == null
                    ? new ArrayList<>(required) : new ArrayList<>(properties.keySet()));
        }

        return nullable ? orNull(out) : out;
    }

    private static Map<String, Object> orNull(Map<String, Object> schema) {
        if (schema.containsKey("anyOf")) {
            return schema;
        }
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("anyOf", List.of(schema, Map.of("type", "null")));
        return wrapper;
    }
}
