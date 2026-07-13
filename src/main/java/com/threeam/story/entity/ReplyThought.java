package com.threeam.story.entity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 답변 앞에 붙는 상담자의 메모. 모델은 [생각] 몇 줄을 쓴 뒤 [답] 뒤에 실제로 할 말을 쓴다
// (형식 지시는 persona.yml — 마커 문자열은 여기와 같아야 한다).
//
//   [생각]
//   읽어줄 것: ...
//   아직 모르는 것: ...
//   물을 장면: ...
//   [답]
//   본문...
//
// 저장 전에 가른다. 화면에는 [답]만 나가고 메모는 messages.thought에 남는다. 다음 턴 프롬프트에도
// 안 싣는다 — 대화 기록은 content만 실리므로 모델은 자기 메모를 다시 보지 않는다(새 답이 자기
// 가설과 안 맞을 때 가설을 지키는 쪽으로 기우는 걸 막으려는 것. 필요해지면 그때 싣는다).
//
// 메모 칸을 두는 이유: 낮은 추론은 생각할 자리가 없어 생각을 말풍선 안에서 한다(미니 리포트 실측).
// 자리를 주면 말풍선이 비고, 답이 이상할 때 읽기가 틀렸는지 말이 틀렸는지를 메모로 가를 수 있다.
// 마커가 없거나 [답] 뒤가 비어 있으면 전부 본문이다 — 형식을 안 지킨 턴이 빈 말풍선으로 가는 것보다 낫다.
public final class ReplyThought {

    public static final String THOUGHT_MARKER = "[생각]";
    public static final String ANSWER_MARKER = "[답]";

    // 대괄호 안 공백과 뒤의 콜론은 흘려도 받는다.
    private static final Pattern THOUGHT = Pattern.compile("\\[\\s*생각\\s*\\]\\s*:?");
    private static final Pattern ANSWER = Pattern.compile("\\[\\s*답\\s*\\]\\s*:?");

    public record Split(String thought, String answer) {
    }

    private ReplyThought() {
    }

    public static Split split(String reply) {
        if (reply == null) {
            return new Split(null, null);
        }
        Matcher answerAt = ANSWER.matcher(reply);
        if (!answerAt.find()) {
            return new Split(null, reply);
        }
        String answer = reply.substring(answerAt.end()).strip();
        if (answer.isEmpty()) {
            return new Split(null, reply);
        }
        String thought = THOUGHT.matcher(reply.substring(0, answerAt.start())).replaceFirst("").strip();
        return new Split(thought.isEmpty() ? null : thought, answer);
    }

    public static boolean hasAnswerMarker(String reply) {
        return reply != null && ANSWER.matcher(reply).find();
    }
}
