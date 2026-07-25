package com.threeam.match.service;

import com.threeam.match.MatchTaxonomy;
import com.threeam.match.entity.ReunionCase;
import com.threeam.match.entity.StoryMatchProfile;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

// 유저 프로필과 사례 한 건의 닮은 정도를 점수로 만든다(분류체계.md 가중치 티어).
// 티어를 쓰는 이유: 겹치는 항목 수를 세면 나이대와 이별 사유가 같은 무게가 돼
// "20대 후반이라 비슷한 사례"가 뽑힌다. 무엇이 닮았느냐가 몇 개 닮았느냐보다 중요하다.
@Component
public class CaseScorer {

    // T1 서브태그 겹침 — 순서가 뜻을 가진다. 주(0번)는 이별을 실제로 당긴 방아쇠이고
    // 부는 밑에 깔린 요인이라, 주끼리 맞는 것과 부끼리 스치는 것은 같은 사건이 아니다.
    private static final int PRIMARY_BOTH = 50;      // 방아쇠가 같음
    // 대상 없는 범용 태그(거짓말신뢰 등)의 방아쇠 일치는 낮게 센다 — 무엇을 숨겼는지가
    // 태그에 없어서 서로 다른 사건이 같은 방아쇠로 잡힌다. 소재를 특정하는
    // 구체 태그가 승부를 가르게 하는 게 목적이다.
    private static final int PRIMARY_BOTH_GENERIC = 30;
    // 절반(25)으로 두면 엇갈린 겹침 두 개가 방아쇠 일치 하나와 동점이 된다 — 겹친 태그 수가
    // 방아쇠 일치를 이기는 순간 티어를 나눈 뜻이 사라지므로, 두 번 엇갈려도 못 넘게 20으로 둔다.
    private static final int PRIMARY_CROSS = 20;     // 한쪽의 방아쇠가 다른 쪽 밑바닥에 깔림
    private static final int SECONDARY_BOTH = 10;    // 배경끼리만 겹침

    // T2 핵심 — 대분류와 이별의 구도(누가 통보했나, 누구 잘못인가)
    private static final int REASON = 30;
    // 대분류는 갈렸지만 한쪽의 서브태그가 다른 쪽 대분류를 가리키는 경우(예: 유저는 본인과실인데
    // 소개팅앱 태그를 달았고 사례는 외도). 루브릭이 경계 케이스에 양쪽 프레임 태그를 실으라고
    // 시키므로, 점수도 그 겹침을 인정해야 지시와 어긋나지 않는다. 정확 일치의 절반.
    private static final int REASON_CROSS = 15;
    private static final int DUMPER = 15;
    // 나떠밀림과 상대는 값은 달라도 이별을 만든 쪽이 같다(상대). 정확 일치보다는 낮게.
    private static final int DUMPER_KINDRED = 10;
    private static final int FAULT = 10;

    // T3 상황 — 지금 어떤 상태인가
    // 연락 상태는 실패 사례에서만 점수가 된다. 재회한 사례는 결과가 곧 연락중이라 값이
    // 전부 같고, 그 축으로 성공 사례끼리 가릴 게 없다. 그런데도 채점하면
    // 무연락, 차단인 유저에게 모든 성공 사례가 12점씩 밀려 나가는 편향만 남는다.
    private static final int CONTACT_STATE = 12;
    private static final int REPEAT_BREAKUP = 8;
    // 상대에게 새 사람이 있는 판은 없는 판과 게임이 다르다 — 사유가 같아도 넘어야 할
    // 장벽이 하나 더 있다. 연락 상태(12)에 준하는 무게로 본다.
    private static final int PARTNER_HAS_NEW = 12;

    // T4 부수 — 배경. 이것만 맞아선 "비슷한 사례"가 되지 않게 낮게 둔다.
    private static final int PERIOD_BUCKET = 6;
    private static final int DATING_BUCKET = 4;
    private static final int AGE_GROUP = 3;

    // 이 아래는 "닮았다"고 말하기 민망한 수준이라 안 보여준다. 억지로 채운 사례가 섞이면
    // 제대로 뽑힌 앞의 사례까지 못 믿게 된다. 대분류(30)만 같고 나머지가 다 어긋난 정도가 경계.
    public static final int MIN_SCORE = 45;

    public int score(StoryMatchProfile profile, ReunionCase target) {
        // 방향 게이트: 통보 주체나 과실이 '나'와 '상대'로 명시 반대인 사례는 태그가 아무리
        // 겹쳐도 남의 이야기다 — 같은 사유여도 겪는 자리가 반대면 공감이 아니라 오독을 만든다.
        // 합의, 미상, 양쪽, 없음은 반대가 아니므로 게이트에 안 걸린다.
        if (opposed(profile.getDumper(), target.getDumper())
                || opposed(profile.getFault(), target.getFault())) {
            return 0;
        }
        return subReasonScore(profile.subReasonList(), target.subReasonList())
                + tierTwo(profile, target)
                + tierThree(profile, target)
                + tierFour(profile, target);
    }

    // 이별을 만든 쪽이 명시적으로 반대인 경우만 반대다. 나떠밀림(상대가 밀어붙여 유저가
    // 말만 꺼낸 이별)은 상대 쪽으로 센다 — 진짜 자기가 식어서 찬 '나'와는 겪는 자리가
    // 반대다. 합의, 미상, 양쪽, 없음은 어느 쪽도 아니라 게이트에 안 걸린다.
    private boolean opposed(String mine, String theirs) {
        String a = drivingSide(mine);
        String b = drivingSide(theirs);
        return a != null && b != null && !a.equals(b);
    }

    private String drivingSide(String value) {
        if ("나".equals(value)) {
            return "나";
        }
        if ("상대".equals(value) || "나떠밀림".equals(value)) {
            return "상대";
        }
        return null;
    }

    // 한쪽의 서브태그가 다른 쪽 대분류를 가리키는가. 같은 소재를 서로 다른 프레임으로
    // 기록한 두 판(랜챗 은닉을 본인과실로 잡았는데 사례는 외도로 기록된 경우)을 잇는다.
    private boolean crossReason(StoryMatchProfile profile, ReunionCase target) {
        return pointsTo(profile.subReasonList(), target.getReason())
                || pointsTo(target.subReasonList(), profile.getReason());
    }

    private boolean pointsTo(List<String> tags, String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return tags.stream().anyMatch(tag -> MatchTaxonomy.reasonsOfTag(tag).contains(reason));
    }

    // 값은 달라도 이별을 만든 쪽이 같은 조합(나떠밀림 vs 상대).
    private boolean kindred(String mine, String theirs) {
        return !Objects.equals(mine, theirs)
                && drivingSide(mine) != null
                && Objects.equals(drivingSide(mine), drivingSide(theirs));
    }

    // 순서 있는 두 목록의 겹침을 위치까지 보고 점수화한다. 가장 센 겹침 하나만 인정하지 않고
    // 모두 합산한다 — 주도 같고 부도 같은 사례는 주만 같은 사례보다 실제로 더 닮았다.
    private int subReasonScore(List<String> mine, List<String> theirs) {
        if (mine.isEmpty() || theirs.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (int i = 0; i < mine.size(); i++) {
            int j = theirs.indexOf(mine.get(i));
            if (j < 0) {
                continue;
            }
            boolean minePrimary = i == 0;
            boolean theirsPrimary = j == 0;
            if (minePrimary && theirsPrimary) {
                score += MatchTaxonomy.isGeneric(mine.get(i)) ? PRIMARY_BOTH_GENERIC : PRIMARY_BOTH;
            } else if (minePrimary || theirsPrimary) {
                score += PRIMARY_CROSS;
            } else {
                score += SECONDARY_BOTH;
            }
        }
        return score;
    }

    private int tierTwo(StoryMatchProfile profile, ReunionCase target) {
        int score = 0;
        if (same(profile.getReason(), target.getReason())) {
            score += REASON;
        } else if (crossReason(profile, target)) {
            score += REASON_CROSS;
        }
        // 미상끼리 맞은 건 "둘 다 모른다"일 뿐 닮은 게 아니다.
        if (!"미상".equals(profile.getDumper()) && same(profile.getDumper(), target.getDumper())) {
            score += DUMPER;
        } else if (kindred(profile.getDumper(), target.getDumper())) {
            score += DUMPER_KINDRED;
        }
        // 미상끼리 맞은 건 dumper와 같은 이유로 가점하지 않는다.
        if (!"미상".equals(profile.getFault()) && same(profile.getFault(), target.getFault())) {
            score += FAULT;
        }
        return score;
    }

    private int tierThree(StoryMatchProfile profile, ReunionCase target) {
        int score = 0;
        if (scoresContact(target) && same(profile.getContactState(), target.getContactState())) {
            score += CONTACT_STATE;
        }
        // 온오프를 겪은 사이끼리만 인정한다 — 둘 다 아닌 건 대다수라 변별력이 없다.
        if (Boolean.TRUE.equals(profile.getRepeatBreakup())
                && Boolean.TRUE.equals(target.getRepeatBreakup())) {
            score += REPEAT_BREAKUP;
        }
        // 온오프와 같은 문법 — 둘 다 해당할 때만 인정한다. 둘 다 아닌 건 대다수라 변별력이 없다.
        if (Boolean.TRUE.equals(profile.getPartnerHasNew())
                && Boolean.TRUE.equals(target.getPartnerHasNew())) {
            score += PARTNER_HAS_NEW;
        }
        return score;
    }

    private int tierFour(StoryMatchProfile profile, ReunionCase target) {
        int score = 0;
        score += sameBucket(profile.getMonthsSinceBreakup(), target.getMonthsSinceBreakup(),
                PERIOD_BUCKETS) ? PERIOD_BUCKET : 0;
        score += sameBucket(profile.getDatingMonths(), target.getDatingMonths(),
                DATING_BUCKETS) ? DATING_BUCKET : 0;
        score += same(profile.getAgeGroup(), target.getAgeGroup()) ? AGE_GROUP : 0;
        return score;
    }

    // 이별 경과: 한 달 안, 석 달 안, 반년 안, 그 뒤. 한 달과 두 달은 다른 시기지만
    // 열 달과 열한 달은 사실상 같은 시기라 뒤로 갈수록 넓게 잡는다.
    private static final int[] PERIOD_BUCKETS = {1, 3, 6};

    // 연애 기간: 반년 안, 1년 안, 2년 안, 그 뒤.
    private static final int[] DATING_BUCKETS = {6, 12, 24};

    private boolean sameBucket(Integer mine, Integer theirs, int[] edges) {
        return mine != null && theirs != null && bucket(mine, edges) == bucket(theirs, edges);
    }

    private int bucket(int value, int[] edges) {
        for (int i = 0; i < edges.length; i++) {
            if (value <= edges[i]) {
                return i;
            }
        }
        return edges.length;
    }

    private boolean same(String mine, String theirs) {
        return mine != null && !mine.isBlank() && Objects.equals(mine, theirs);
    }

    // 재회한 사례의 연락 상태는 결과가 정해준 값이라 겹쳐도 닮은 지점이 아니다.
    // 화면 태그도 같은 이유로 안 붙인다 — 무연락인 유저의 카드에 "연락 중이 같음"이 뜬다.
    private boolean scoresContact(ReunionCase target) {
        return !"성공".equals(target.getOutcome());
    }

    // 이 사례가 왜 뽑혔는지를 화면에 그대로 실을 태그(무게 순, 최대 4개).
    //
    // 겹친 축만 태그가 된다 — 그래서 화면에 뜨는 값은 전부 유저가 이미 자기 입으로 말한 것이고,
    // 서비스가 유저에게 새 이름표를 붙이는 일이 없다. 안 겹친 사례의 값은 애초에 안 나간다.
    //
    // 값을 띄울 수 있는 항목은 값으로, 아닌 것은 축 이름으로 떨어진다(MatchTaxonomy.displayTag).
    // 통보 주체는 값을 아예 안 띄운다 — 차인 쪽에게 "차임"이라는 이름표가 남의 카드에 박혀 보이고,
    // 나떠밀림은 화면에 쓸 말이 없다.
    //
    // 4개까지만: 카드 하나가 태그밭이 되면 무엇이 진짜 겹침인지가 흐려지고, 뒤로 갈수록
    // 무게가 낮은 축이라 다섯 번째부터는 "닮았다"의 근거가 아니라 장식이다.
    public List<String> matchedTags(StoryMatchProfile profile, ReunionCase target) {
        List<String> tags = new java.util.ArrayList<>();
        List<String> mine = profile.subReasonList();
        List<String> theirs = target.subReasonList();
        String primaryTag = null;
        String secondaryTag = null;
        for (int i = 0; i < mine.size(); i++) {
            int j = theirs.indexOf(mine.get(i));
            if (j < 0) {
                continue;
            }
            if (i == 0 || j == 0) {
                if (primaryTag == null) {
                    primaryTag = mine.get(i);
                }
            } else if (secondaryTag == null) {
                secondaryTag = mine.get(i);
            }
        }
        if (primaryTag != null) {
            tags.add(labelled(primaryTag, "이별의 결정적 계기"));
        }
        if (same(profile.getReason(), target.getReason())) {
            tags.add(labelled(profile.getReason(), "이별 사유"));
        }
        if (primaryTag == null && secondaryTag != null) {
            tags.add(labelled(secondaryTag, "이별의 배경 사정"));
        }
        if (!"미상".equals(profile.getDumper()) && same(profile.getDumper(), target.getDumper())) {
            tags.add("헤어지자고 한 쪽");
        } else if (kindred(profile.getDumper(), target.getDumper())) {
            tags.add("이별을 원한 쪽");
        }
        if (scoresContact(target) && same(profile.getContactState(), target.getContactState())) {
            tags.add(labelled(profile.getContactState(), "지금 연락 상태"));
        }
        // 둘 다 false인 일치는 안 센다 — 새 사람이 없는 건 대부분의 판이라 닮은 지점이 아니다
        if (Boolean.TRUE.equals(profile.getPartnerHasNew())
                && Boolean.TRUE.equals(target.getPartnerHasNew())) {
            tags.add("상대에게 새 사람");
        }
        if (Boolean.TRUE.equals(profile.getRepeatBreakup())
                && Boolean.TRUE.equals(target.getRepeatBreakup())) {
            tags.add("재회 이력");
        }
        if (sameBucket(profile.getMonthsSinceBreakup(), target.getMonthsSinceBreakup(), PERIOD_BUCKETS)) {
            tags.add("이별 후 경과");
        }
        // 사례 쪽 값을 쓴다 — 같은 버킷이어도 숫자는 다르고, 유저가 보는 것은 이 사례의 기간이다
        if (sameBucket(profile.getDatingMonths(), target.getDatingMonths(), DATING_BUCKETS)) {
            tags.add(months(target.getDatingMonths()) + " 만남");
        }
        return tags.size() > 4 ? tags.subList(0, 4) : tags;
    }

    private String labelled(String value, String axisName) {
        String display = MatchTaxonomy.displayTag(value);
        return display != null ? display : axisName;
    }

    private String months(int m) {
        if (m < 12) {
            return m + "개월";
        }
        int years = m / 12;
        int rest = m % 12;
        return rest == 0 ? years + "년" : years + "년 " + rest + "개월";
    }
}
