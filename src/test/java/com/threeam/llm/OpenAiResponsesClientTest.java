package com.threeam.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.assessment.service.ReadingLlm;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpenAiResponsesClientTest {

    @Test
    void parseResponses_collectsTextSearchesAndSources() throws Exception {
        String json = """
                {"status":"completed","output":[
                  {"type":"web_search_call","id":"ws_1","status":"completed",
                   "action":{"type":"search","query":"이별 후 재회 확률 연구","sources":[{"url":"https://a.example/1"}]}},
                  {"type":"message","role":"assistant","content":[
                    {"type":"output_text","text":"본문 첫 줄 [출처](https://b.example/2) 끝. 연구가 있습니다. (pmc.ncbi.nlm.nih.gov)",
                     "annotations":[{"type":"url_citation","url":"https://b.example/2","title":"B"}]}]}
                ],"usage":{"input_tokens":100,"output_tokens":50}}
                """;
        OpenAiResponsesClient.ResponsesOutput out =
                OpenAiResponsesClient.parseResponses(new ObjectMapper().readTree(json));
        assertThat(out.text()).isEqualTo("본문 첫 줄 출처 끝. 연구가 있습니다.");
        assertThat(out.searches()).isEqualTo(1);
        assertThat(out.queries()).containsExactly("이별 후 재회 확률 연구");
        assertThat(out.sources()).containsExactly("https://a.example/1", "https://b.example/2");
        assertThat(out.refusal()).isNull();
    }

    @Test
    void parseResponses_readsRefusal() throws Exception {
        String json = """
                {"status":"completed","output":[
                  {"type":"message","role":"assistant","content":[{"type":"refusal","refusal":"안 함"}]}
                ]}
                """;
        OpenAiResponsesClient.ResponsesOutput out =
                OpenAiResponsesClient.parseResponses(new ObjectMapper().readTree(json));
        assertThat(out.refusal()).isEqualTo("안 함");
        assertThat(out.text()).isBlank();
    }

    // 판독(B) 요청 — 검색 도구와 strict json_schema가 한 본문에 같이 실려야 한다.
    @Test
    @SuppressWarnings("unchecked")
    void responsesBody_withSchema_setsStrictJsonFormatAndSearchTool() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setReasoningEffort("low");
        OpenAiResponsesClient client = new OpenAiResponsesClient(new ObjectMapper(), properties);
        Map<String, Object> schema = StrictJsonSchema.fromGoogle(ReadingLlm.JUDGEMENT_SCHEMA);

        Map<String, Object> body = client.responsesBody(
                List.of(ChatMessage.system("지시"), ChatMessage.user("사연")), "gpt-x", "medium", 5, schema);

        assertThat(body.get("model")).isEqualTo("gpt-x");
        List<Map<String, Object>> input = (List<Map<String, Object>>) body.get("input");
        assertThat(input).extracting(m -> m.get("role")).containsExactly("system", "user");
        List<Map<String, Object>> tools = (List<Map<String, Object>>) body.get("tools");
        assertThat(tools.get(0)).containsEntry("type", "web_search").containsEntry("search_context_size", "medium");
        assertThat(body.get("max_tool_calls")).isEqualTo(5);
        assertThat(body.get("reasoning")).isEqualTo(Map.of("effort", "low"));

        Map<String, Object> format = (Map<String, Object>) ((Map<String, Object>) body.get("text")).get("format");
        assertThat(format).containsEntry("type", "json_schema").containsEntry("name", "reading")
                .containsEntry("strict", true);
        Map<String, Object> sent = (Map<String, Object>) format.get("schema");
        assertThat(sent.get("type")).isEqualTo("object");
        assertThat(sent.get("additionalProperties")).isEqualTo(false);
        assertThat((List<String>) sent.get("required")).containsExactlyInAnyOrder("name", "cards", "summary", "grade", "evidence");
        Map<String, Object> grade = (Map<String, Object>) ((Map<String, Object>) sent.get("properties")).get("grade");
        assertThat((List<String>) grade.get("enum")).containsExactly("매우 낮음", "낮음", "높음", "매우 높음");
    }

    @Test
    void responsesBody_effortOverride_replacesCommonEffort() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setReasoningEffort("low");
        OpenAiResponsesClient client = new OpenAiResponsesClient(new ObjectMapper(), properties);

        Map<String, Object> body = client.responsesBody(List.of(ChatMessage.user("사연")), "gpt-x", "", 0, null, "xhigh");

        assertThat(body.get("reasoning")).isEqualTo(Map.of("effort", "xhigh"));
    }

    @Test
    void responsesBody_withoutSchema_hasNoTextFormat() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setReasoningEffort("");
        OpenAiResponsesClient client = new OpenAiResponsesClient(new ObjectMapper(), properties);

        Map<String, Object> body = client.responsesBody(List.of(ChatMessage.user("사연")), "gpt-x", "", 0, null);

        assertThat(body).doesNotContainKeys("text", "max_tool_calls", "reasoning");
    }

    @Test
    void parseResponses_jsonModeKeepsArrayBrackets() throws Exception {
        String json = """
                {"status":"completed","output":[
                  {"type":"message","role":"assistant","content":[
                    {"type":"output_text","text":"{\\"cards\\":[{\\"finding\\":\\"근거 [pubmed](https://a.example/1) 끝\\"}],\\"name\\":\\"이름\\"}",
                     "annotations":[]}]}
                ],"usage":{"input_tokens":1,"output_tokens":1}}
                """;
        OpenAiResponsesClient.ResponsesOutput out =
                OpenAiResponsesClient.parseResponses(new ObjectMapper().readTree(json), false);
        assertThat(out.text()).startsWith("{\"cards\":[{");
        assertThat(OpenAiResponsesClient.stripCitations("근거 [pubmed](https://a.example/1) 끝 (pmc.ncbi.nlm.nih.gov)"))
                .isEqualTo("근거 pubmed 끝");
    }
}
