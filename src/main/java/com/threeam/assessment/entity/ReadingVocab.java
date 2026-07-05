package com.threeam.assessment.entity;

import java.util.List;
import java.util.Map;

// 정밀 판독의 허용 어휘 사전. 스키마(생성 강제)와 파서(저장 전 검증)가 같은 목록을 쓴다.
public final class ReadingVocab {

    private ReadingVocab() {
    }

    // 장의 역할 태그 — 채점용 메타데이터다. 유저 비노출이고 생성을 제어하지 않는다.
    // 옛 9개는 한 목록에 분석 대상, 분석 방식, 수행 작업이 섞여 있어 분류 비용만 들었다.
    // 판독 순서(궤적 -> 맞물림 -> 현재 상태, 필요할 때만 오해 교정)와 같은 축으로 줄였다.
    public static final List<String> CHAPTER_ROLES = List.of(
            "TRAJECTORY", "INTERACTION", "CURRENT_STATE", "MISREAD_CORRECTION");

    // 진단 항목의 키와 화면 라벨. 요인 이름(대체자 등)이 그대로 겹치는 것도 있지만,
    // 이건 채점 슬롯이 아니라 "확률을 만든 진단"의 사용자 지면 어휘다 — 판독이 유저 언어로
    // 다시 쓴다. 백엔드가 순위와 방향을 확정해 내려주고 판독은 그 순서를 바꾸지 않는다.
    public static final Map<String, String> DIAGNOSIS_LABELS = Map.of(
            "partnerSignal", "상대신호",
            "breakupReason", "이별사유",
            "resolve", "이별결심",
            "replacement", "대체자",
            "relationshipAsset", "관계자산",
            "contact", "접점",
            "userResponse", "현재대응",
            "partnerPattern", "과거패턴",
            "currentBarrier", "현재장벽",
            "custom", "추가진단");

    public static final List<String> DIAGNOSIS_KEYS = List.copyOf(DIAGNOSIS_LABELS.keySet());

    // 진단 항목의 등급. 요인 판정과 같은 어휘를 쓴다 — 화면도 이 말을 그대로 보여준다.
    public static final List<String> LEVELS = List.of(
            "매우유리", "유리", "중립", "불리", "매우불리");

    // 항목 묶음. 핵심은 거의 항상, 조건부는 근거가 있을 때만, 추가는 1호출이 만든 독립 변수.
    public static final List<String> GROUPS = List.of("CORE", "CONDITIONAL", "EXTRA");

    // 근거 상태. 없다는 것이 확인된 것(ABSENCE_CONFIRMED)과 아직 모르는 것(PARTIAL)은 다르다.
    public static final List<String> EVIDENCE_STATES = List.of(
            "CONFIRMED", "ABSENCE_CONFIRMED", "PARTIAL");

    // 시간이 작용하는 방식과, 그래서 어떻게 움직일지의 방향(1호출이 판정).
    public static final List<String> TIME_STATES = List.of(
            "COOLING_HELPFUL", "REPAIR_WINDOW", "PASSIVE_WAITING_HARMFUL",
            "BOUNDARY_FIRST", "EVENT_BASED", "CLOSED");

    public static final List<String> ACTION_BIASES = List.of(
            "WAIT_TO_BOUNDARY", "SHORT_COOLING_THEN_RECONTACT", "ONE_REPAIR_ATTEMPT",
            "DO_NOT_WAIT_PASSIVELY", "USE_EXISTING_EVENT", "NO_FURTHER_CONTACT");

    // 판독이 제안하는 행동 자세(actionPlan.stance).
    public static final List<String> STANCES = List.of(
            "WAIT_TO_BOUNDARY", "SHORT_COOLING_THEN_RECONTACT", "ONE_REPAIR_ATTEMPT",
            "USE_EXISTING_EVENT", "STOP_CONTACT", "HOLD_AND_REASSESS");

    // 뒤늦은 후회 마크의 강도. 반대 증거가 남았으면 MODERATE, 여러 시점의 사실이 겹치면 STRONG.
    public static final List<String> REGRET_STRENGTHS = List.of("MODERATE", "STRONG");
}
