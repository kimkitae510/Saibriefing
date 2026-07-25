package com.threeam.llm;

// JSON 모드(responseMimeType)를 강제해도 모델이 드물게 코드펜스나 앞뒤 잡설을 붙인다.
// 파싱 전에 첫 '{'부터 마지막 '}'까지만 잘라 살려본다. 그래도 JSON이 아니면 기존대로
// 파싱 실패로 흐른다(로그 관측 유지).
public final class LlmJson {

    private LlmJson() {
    }

    public static String salvage(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        return (start >= 0 && end > start) ? raw.substring(start, end + 1) : raw;
    }
}
