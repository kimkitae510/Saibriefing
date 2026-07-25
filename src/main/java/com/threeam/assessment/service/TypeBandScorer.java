package com.threeam.assessment.service;

import com.threeam.assessment.dto.ReunionDiagnosis;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.BreakupType;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.JumpRule;
import com.threeam.assessment.entity.ReadingVocab;
import com.threeam.assessment.entity.ReplacementStage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

// 확률 계산 v2: 유형이 대역을 정하고(1층), 요인 판정이 대역 안에서 조정한다(2층).
// LLM은 유형과 요인의 '판정'만 내리고 숫자는 전부 여기 상수다 — 같은 판정이면 언제나 같은 확률.
// (v1 ReunionScorer는 LLM이 신호마다 점수를 발명해 같은 사연에도 확률이 출렁였다.)
// 대역과 폭의 근거는 루브릭 문서(루브릭v2.md) — 커뮤니티/업계 조사 기반 서열에 우리 설계값.
@Component
public class TypeBandScorer {

    private static final int ABS_MIN = 3;
    private static final int ABS_MAX = 95; // 96~100은 상대의 유효한 재회 제안(확정 100) 몫

    // 대역은 앵커지 칸막이가 아니다 — 폭을 12~16으로 줄였다(구 20~35). 넓은 대역은 유형
    // 구분을 무디게 하고 경계 오판 한 번의 낙차를 키웠다. 0~100을 꽉 채울 이유도 없다:
    // 극단은 헤드룸(±10)과 절대 3~95가 맡고, 대역 안 변별은 요인이 한다.
    //
    // 상단(충동, 상황)은 일반 인구의 재회율이 아니라 '아직 안 돌아와서 분석을 받으러 온
    // 사람'의 값이어야 한다. 홧김에 헤어지고 사흘 뒤 붙은 커플은 이 서비스에 오지 않는다 —
    // 빨리 붙는 유형일수록 표본에서 빠져나가므로 그만큼 낮춘다.
    // 하단은 반대다. 신뢰붕괴 상한 15는 자백하고 검증까지 받아들인 판(사례 실측 2건)을
    // 표현하지 못했다. 환승은 리바운드 실패율이 높아 시간이 장벽을 치우는 반면 권태식음은
    // 치울 장벽조차 없어(항구적 사유), 벌려뒀던 둘의 간극을 좁힌다.
    private static final Map<BreakupType, Band> BANDS = new EnumMap<>(Map.of(
            BreakupType.IMPULSIVE, new Band(52, 68),
            BreakupType.SITUATIONAL, new Band(46, 62),
            BreakupType.EXTERNAL, new Band(36, 50),
            BreakupType.BURNOUT, new Band(20, 33),
            BreakupType.FADED, new Band(20, 32),
            BreakupType.TRANSFER, new Band(15, 27),
            BreakupType.RESOLVED, new Band(10, 22),
            BreakupType.TRUST_BROKEN, new Band(6, 18)));

    // 점프는 이별 후에 관측된 상태다. 재회는 '왜 헤어졌나'보다 '이별 후에 무엇이 있었나'로
    // 더 잘 예측되므로(온오프 종단 연구) 점프가 사유보다 무겁다. 다만 사유를 지우지는
    // 않는다 — 통째로 교체하던 구조에서는 외도로 헤어진 판에 안부 연락 한 번이 오면 사유가
    // 사라지고 접촉재개 대역만 남았다. pull은 사유 대역을 점프 쪽으로 끌어당기는 비율이고,
    // 1.0이면 종전과 같은 완전 교체다.
    //
    // up=true는 좋은 소식(올리기만 한다), false는 나쁜 소식(내리기만 한다). 연락이 왔다는
    // 사실이 확률을 낮추는 근거가 될 수는 없다 — 사유 대역이 점프 대역보다 이미 높을 때
    // 끌어당김이 확률을 깎던 것을 막는 단조성 제약이다. 연락의 세부는 상대신호 요인이 잰다.
    private static final Map<JumpRule, Jump> JUMPS = new EnumMap<>(Map.of(
            // 상대의 마음이 열려 있음이 확인된 판. 이별을 원하지 않은 쪽이 상대라는 것은
            // 온오프 연구에서 1차 재회의 가장 강한 예측 인자다.
            JumpRule.USER_DUMPED, new Jump(new Band(65, 80), 0.75, true),
            JumpRule.USER_DUMPED_FAINT, new Jump(new Band(50, 68), 0.65, true),
            JumpRule.USER_DUMPED_NONE, new Jump(new Band(12, 24), 0.75, false),
            // 문은 다시 열렸지만 내용은 안부뿐 — 사유를 지울 만한 관측이 아니라 pull이 가장 낮다.
            JumpRule.PARTNER_RECONTACT, new Jump(new Band(30, 45), 0.55, true),
            JumpRule.PARTNER_HINT, new Jump(new Band(65, 80), 0.75, true),
            // 장벽이 사라진 것은 문이 열린 것보다 무겁고 돌아오겠다는 말보다 가볍다. 다만
            // 조건이 치워져도 감정이 남았는지는 별개라 사유를 절반쯤만 끌어당긴다.
            JumpRule.BARRIER_CLEARED, new Jump(new Band(42, 58), 0.60, true),
            // 같은 사유로 헤어지고도 매번 돌아온 관계 — 패턴이 입증됨.
            JumpRule.REPEAT_CYCLE, new Jump(new Band(58, 72), 0.75, true),
            JumpRule.PARTNER_CLOSED, new Jump(new Band(8, 18), 0.85, false),
            // 유일한 pull 1.0 — 구조적으로 닫힌 판이라 사유도 요인도 이걸 못 이긴다.
            JumpRule.PARTNER_MARRIED, new Jump(new Band(3, 8), 1.00, false)));

    // 요인별 조정 폭(매우X = 전체 폭, X = 절반). 선언 순서가 무게 순서와 일치한다.
    private static final Map<FactorName, Integer> WIDTHS = new EnumMap<>(Map.of(
            FactorName.PARTNER_SIGNAL, 10,
            FactorName.REPLACEMENT, 4,   // 유리(부재 확인)일 때만 이 값. 불리는 stage가 정한다
            FactorName.USER_CONDUCT, 8,
            FactorName.NOTICE_TONE, 6,
            FactorName.PARTNER_PATTERN, 5,
            FactorName.RELATIONSHIP_ASSET, 6,
            FactorName.CONTACT_PATH, 4));

    // 대체자 불리의 세분 폭.
    private static final int REPLACEMENT_SEEING = 6;
    private static final int REPLACEMENT_SETTLED = 12;

    // 요인이 대역 밖으로 나갈 수 있는 여유. 요인 폭의 합은 43인데 대역은 12~16이라, 대역에
    // 가둬두면 다섯 개쯤은 계산에 실리지도 못하고 클램프에 잘렸다(정착 하한 관통과 상대신호
    // 상한 관통은 그 손실을 특수 규칙으로 때우던 것이라 여기에 흡수하고 지웠다).
    private static final int HEADROOM = 10;

    // 유형은 하나다. 두 유형의 중간을 잡는 경계 유형은 두지 않는다 — 맞은 판까지 가운데로
    // 끌어내리고 틀린 판의 흔적을 지운다. 경계는 완충이 아니라 유형별 판별 관측으로 가른다.
    public int apply(BreakupType type, JumpRule jumpRule, List<AssessmentFactor> factors) {
        Jump jump = JUMPS.get(jumpRule == null ? JumpRule.NONE : jumpRule);
        Band base = BANDS.get(type);
        Band band = base == null ? jump.band() : pull(base, jump);
        int score = (band.lo() + band.hi()) / 2;
        boolean settled = false;
        for (AssessmentFactor factor : factors) {
            score += delta(factor);
            settled = settled || isSettled(factor);
        }
        // 결정적 점프(pull 1.0)는 요인에게 여유를 주지 않는다 — 그게 '결정적'의 뜻이다.
        int headroom = jump != null && jump.pull() >= 1.0 ? 0 : HEADROOM;
        // 하향 점프는 아래로만 칸막이를 세운다. 점프를 발동시킨 사실(차단, 정리 요구)을
        // 요인이 상대신호와 접점과 통보온도에서 각각 다시 세면, 겹친 감점이 대역을 관통해
        // 절대 하한에 박힌다. 대역을 정한 사실이 대역 안에서 또 일하면 안 되고, 이 중복은 지시로 못 막아 코드가 막는다.
        // 위쪽은 그대로 연다 — 닫힌 판에서 진짜 유리한 관측이 나오면 그건 세야 한다.
        int floorRoom = jump != null && !jump.up() ? 0 : headroom;
        // 새 연인 '정착'만 하한을 아예 안 본다. 상대가 다른 사람과 자리를 잡았다는 것은
        // 이별 사유가 무엇이었든 그 위에서 다시 읽히는 사실이라, 대역이 받쳐주면 안 된다.
        int lo = settled ? ABS_MIN : band.lo() - floorRoom;
        score = Math.max(lo, Math.min(band.hi() + headroom, score));
        return Math.max(ABS_MIN, Math.min(ABS_MAX, score));
    }

    private Band pull(Band base, Jump jump) {
        if (jump == null) {
            return base;
        }
        return new Band(toward(base.lo(), jump.band().lo(), jump),
                toward(base.hi(), jump.band().hi(), jump));
    }

    private int toward(int from, int to, Jump jump) {
        int moved = (int) Math.round(from + jump.pull() * (to - from));
        return jump.up() ? Math.max(from, moved) : Math.min(from, moved);
    }

    private int delta(AssessmentFactor factor) {
        FactorLevel level = factor.getLevel();
        if (level == FactorLevel.NEUTRAL) {
            return 0;
        }
        if (factor.getName() == FactorName.REPLACEMENT && level.unfavorableSide()) {
            return isSettled(factor) ? -REPLACEMENT_SETTLED : -REPLACEMENT_SEEING;
        }
        int width = WIDTHS.get(factor.getName());
        int size = (level == FactorLevel.STRONG_FAVORABLE || level == FactorLevel.STRONG_UNFAVORABLE)
                ? width
                : Math.max(1, width / 2);
        return level.favorableSide() ? size : -size;
    }

    private boolean isSettled(AssessmentFactor factor) {
        return factor.getName() == FactorName.REPLACEMENT
                && factor.getLevel().unfavorableSide()
                && factor.getStage() == ReplacementStage.SETTLED;
    }

    // ── 판독(2호출) payload용 — 화면 진단 카드에 순위와 등급 붙이기 ────────────
    // 문장(headline, reading)은 1호출이 쓴다. 백엔드는 그 문장이 어느 채점에서 나온
    // 것인지(source)를 보고 순위, 등급, 묶음, 근거 상태를 붙인다.
    // 순위는 "좋은 것부터"가 아니라 확률을 실제로 많이 움직인 것부터다.

    // 표시 등급 5단계. 화면 밴드 라벨(assessmentScale의 매우낮음~매우높음)과 같은 경계 —
    // 2호출이 등급 기준을 자체 발명하지 않게 백엔드가 확정한다.
    public String level(int probability) {
        if (probability >= 82) {
            return "VERY_HIGH";
        }
        if (probability >= 65) {
            return "HIGH";
        }
        if (probability >= 45) {
            return "MID";
        }
        return probability >= 25 ? "LOW" : "VERY_LOW";
    }

    // 유형이 "매우"로 읽히는 대역 위치. 상단은 충동형(중심 60)만, 하단은 환승형(21) 아래.
    // 소진, 권태(26)는 상한이 33이라 불리로 두고, 되돌릴 통로 자체가 좁은 유형만 매우불리로 본다.
    private static final int STRONG_UP_MID = 58;
    private static final int STRONG_DOWN_MID = 22;

    // 핵심 진단(거의 항상 검토)과 조건부 진단(근거가 있을 때만)의 구분. 그 밖은 추가 진단이다.
    private static final List<String> CORE_KEYS =
            List.of("breakupReason", "resolve", "partnerSignal", "currentBarrier");
    private static final List<String> CONDITIONAL_KEYS =
            List.of("replacement", "relationshipAsset", "contact", "userResponse", "partnerPattern");

    // 화면에 올릴 카드 상한(v11.1의 최대 8).
    private static final int CARD_MAX = 8;

    // 1호출이 쓴 화면 진단에 채점 결과를 붙인다. source가 어느 채점을 가리키는지로
    // 등급과 순위가 정해지고, 채점이 없는 항목(현재장벽, 추가 진단)은 뒤로 간다.
    public List<ReadingLlm.DiagnosisCard> cards(ReunionDiagnosis diagnosis, BreakupType type,
                                                JumpRule jumpRule, List<AssessmentFactor> factors) {
        if (diagnosis.displayDiagnosis() == null) {
            return List.of();
        }
        Map<FactorName, AssessmentFactor> byName = new EnumMap<>(FactorName.class);
        factors.forEach(f -> byName.put(f.getName(), f));
        Jump jump = JUMPS.get(jumpRule == null ? JumpRule.NONE : jumpRule);

        List<Scored> scored = new ArrayList<>();
        for (ReunionDiagnosis.DisplayItem item : diagnosis.displayDiagnosis().items()) {
            String source = item.source() == null ? "" : item.source();
            String level = "중립";
            String evidenceState = "PARTIAL";
            int magnitude = 0;
            if (source.startsWith("FACTOR:")) {
                AssessmentFactor factor = byName.get(FactorName.fromLabel(source.substring(7)));
                if (factor != null) {
                    level = factor.getLevel().label();
                    magnitude = Math.abs(delta(factor));
                    evidenceState = evidenceState(factor);
                    // 점프가 걸린 판의 상대신호는 점프가 만든 크기가 실제 기여다.
                    if (jump != null && factor.getName() == FactorName.PARTNER_SIGNAL) {
                        magnitude = Math.max(magnitude, (int) Math.round(10 + jump.pull() * 10));
                        level = jumpLevel(jump, level);
                    }
                }
            } else if ("BREAKUP_TYPE".equals(source) && type != null) {
                Band band = BANDS.get(type);
                int mid = (band.lo() + band.hi()) / 2;
                magnitude = Math.abs(mid - 50);
                boolean strong = mid >= STRONG_UP_MID || mid <= STRONG_DOWN_MID;
                level = mid >= 50 ? (strong ? "매우유리" : "유리") : (strong ? "매우불리" : "불리");
                evidenceState = "CONFIRMED";
            } else {
                // 현재장벽, 추가 진단 — 채점에 없는 항목이라 방향은 1호출의 문장에 맡기고
                // 등급은 중립으로 둔다. 확률 기여가 없으니 순위도 뒤다.
                evidenceState = "CONFIRMED";
            }
            scored.add(new Scored(item, level, evidenceState, magnitude));
        }

        scored.sort(Comparator.<Scored>comparingInt(x -> "중립".equals(x.level()) ? 1 : 0)
                .thenComparing(Comparator.comparingInt(Scored::magnitude).reversed()));

        List<ReadingLlm.DiagnosisCard> cards = new ArrayList<>();
        for (Scored x : scored) {
            if (cards.size() >= CARD_MAX) {
                break;
            }
            ReunionDiagnosis.DisplayItem item = x.item();
            List<String> factIds = new ArrayList<>();
            if (item.factIndexes() != null) {
                item.factIndexes().forEach(i -> factIds.add(String.format("F%02d", i)));
            }
            cards.add(new ReadingLlm.DiagnosisCard(item.key(), item.label(), group(item.key()),
                    cards.size() + 1, x.level(), x.evidenceState(),
                    item.headline(), item.reading(), factIds));
        }
        return cards;
    }

    private record Scored(ReunionDiagnosis.DisplayItem item, String level, String evidenceState,
                          int magnitude) {
    }

    private String group(String key) {
        if (CORE_KEYS.contains(key)) {
            return "CORE";
        }
        return CONDITIONAL_KEYS.contains(key) ? "CONDITIONAL" : "EXTRA";
    }

    // 근거 상태. "없다고 확인된 것"과 "아직 모르는 것"은 화면에서 다르게 읽혀야 한다 —
    // 대체자 유리는 부재가 확인된 판이고, 근거 없는 중립은 아직 못 물어본 판이다.
    private String evidenceState(AssessmentFactor factor) {
        String evidence = factor.getEvidence();
        if (evidence == null || evidence.isBlank() || NO_EVIDENCE.equals(evidence)) {
            return "PARTIAL";
        }
        return factor.getName() == FactorName.REPLACEMENT && factor.getLevel().favorableSide()
                ? "ABSENCE_CONFIRMED" : "CONFIRMED";
    }

    private static final String NO_EVIDENCE = "근거 없음";

    // 점프가 대역을 끌어당긴 판에서는 상대신호 등급이 그 방향을 따라간다 —
    // 요인 판정만 쓰면 "문이 열렸다"는 관측이 화면에서 중립으로 보인다.
    private String jumpLevel(Jump jump, String factorLevel) {
        if ("중립".equals(factorLevel)) {
            return jump.up() ? "유리" : "불리";
        }
        return factorLevel;
    }

    private record Band(int lo, int hi) {
    }

    private record Jump(Band band, double pull, boolean up) {
    }
}
