package com.threeam.assessment.entity;

// 분석 리포트의 큰 갈래.
// POSSIBLE=확률 산출, INSUFFICIENT=판단 근거 부족(확률 대신 대화를 더 요청, 히스토리 미저장).
// DATING(사귀는 중 잠금)은 폐기 — 만나는 중 사연은 게이트가 INSUFFICIENT 안내로 처리한다
//   (저장 없음, 무차감). 잠금 화면과 번복 버튼이 정규 동선(대화로 정정 후 재분석)과 겹치던 것 정리.
//   과거 데이터 호환 위해 상수만 남겨둔다.
// REUNITED=재회에 성공해 다시 만나는 중 — 목표를 이룬 상태라 확률 산출이 없고 전용 화면(축하)으로 보여준다.
// LET_GO(놓아주기)는 폐기 — "못 놓아서 온 사람"에게 놓아주라는 판정은 하지 않는다.
//   가망 낮은 케이스도 낮은 확률(POSSIBLE)로 표현한다. 과거 데이터 호환 위해 상수만 남겨둔다.
// 폭력/학대 전용 잠금(NOT_ADVISABLE)은 만들었다 폐기 — 그런 관계에서도 재회는 실제로 일어나므로
//   확률은 사실을 재야 한다. 확률은 정직하게 내고 안전 우려는 총평이 사실로만 담는다.
public enum ReunionVerdict {
    POSSIBLE,
    INSUFFICIENT,
    @Deprecated
    DATING,
    REUNITED,
    @Deprecated
    LET_GO
}
