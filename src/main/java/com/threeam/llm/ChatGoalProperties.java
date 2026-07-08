package com.threeam.llm;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 탐색 채팅이 채워야 할 목표 목록과 그 판정 지시. 실문구는 로컬 goals.yml(gitignore)로 주입한다.
// 목표가 무엇인지(무엇을 물어야 판독이 서는지)는 서비스 자산이라 코드에 두지 않는다 —
// 코드는 목록을 읽어 채워진 것과 남은 것을 가르고 프롬프트에 데이터로 실을 뿐이다.
@Getter
@Setter
@ConfigurationProperties(prefix = "llm.goals")
public class ChatGoalProperties {

    // 목표 판정 호출의 지시문. 비면 판정을 돌리지 않는다(목표 없는 자유 대화로 돈다).
    private String judge = "";

    // 유저 발화가 이만큼 쌓이면 목표가 덜 찼어도 마무리로 넘긴다 — 회피하는 유저에게
    // 같은 질문이 영영 이어지지 않게 하는 탈출구. 0이면 상한 없음.
    private int maxUserTurns = 12;

    private List<Goal> items = new ArrayList<>();

    @Getter
    @Setter
    public static class Goal {
        // 저장 키(story_goals.goal_key). 바꾸면 그 키로 저장된 판정이 못 찾는 값이 된다.
        private String key;
        // 사람이 읽는 이름. 프롬프트에 그대로 실린다.
        private String name;
        // 무엇이 관측되면 채워진 것으로 보는지 — 판정 호출이 읽는 기준.
        private String filledWhen;
        // 관측 질문의 꼴 — 상담자가 이 목표를 물을 때 어떤 식으로 묻는지의 힌트.
        private String ask;
    }

    public Goal find(String key) {
        return items.stream().filter(g -> key != null && key.equals(g.getKey())).findFirst().orElse(null);
    }

    public boolean enabled() {
        return judge != null && !judge.isBlank() && !items.isEmpty();
    }
}
