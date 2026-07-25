package com.threeam.match.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 유저 사연과 견줄 참조 사례 한 건. DB 엔티티가 아니다 — 사례는 서비스 자산이라
// 로컬 파일에만 두고 CaseStore가 시동 때 메모리로 읽는다(저장소, DB 어느 쪽에도 안 올린다).
// 분류 어휘(reason, subReasons)는 사례와 유저 프로필이 반드시 같은 사전을 써야 겹침이 잡힌다 —
// 어휘가 어긋나면 T1 점수가 통째로 0이 된다(분류체계 문서).
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReunionCase {

    // 파일엔 없고 CaseStore가 배열 순서로 부여한다. 동점 정렬의 기준.
    private Long id;

    private String story;
    private String gender;
    private String ageGroup;
    private String reason;
    // 파일에선 순서 있는 배열 그대로 — 0번이 주(방아쇠), 뒤가 밑에 깔린 요인.
    private List<String> subReasons;
    private String dumper;
    private Integer datingMonths;
    private String postBehavior;
    private String contactState;
    private Integer reunionMonths;
    private Integer monthsSinceBreakup;
    private String periodLabel;
    private String reunionRecord;
    private String outcome;
    private String fault;
    private String length;
    private String tone;
    private Boolean repeatBreakup;
    // 상대에게 새 사람이 있었는가. 이 축이 없으면 새 연인이 눈앞에 있는 사연에
    // 그런 장벽이 없던 사례가 올라온다 — 사유는 같아도 판이 다르다.
    private Boolean partnerHasNew;
    private String sourceType;

    // CaseStore 전용 — 로드 직후 한 번만 부른다.
    public void assignId(Long id) {
        this.id = id;
    }

    public List<String> subReasonList() {
        return subReasons == null ? List.of() : subReasons;
    }
}
