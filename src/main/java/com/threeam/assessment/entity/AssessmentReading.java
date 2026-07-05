package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 정밀 판독(2호출) 결과. 판정(assessments)과 1:1 — 판정이 확정된 뒤 그 위에 스토리 리포트를 단다.
// 본문(body)은 스토리북 산출물(ReadingDraft) JSON 통짜다 — 리포트 구조가 빠르게 바뀌는 중이라
// 컬럼으로 파면 개편마다 DDL이 따라붙는다. 표시 전용이라 쿼리할 일도 없다.
// state 4개는 컬럼으로 뽑다가 걷어냈다(DROP 완료) — 쓰기만 하고 읽는 곳이 없었다.
// 값 자체는 body JSON 안에 그대로 남아 있어 골든셋 회귀에 필요하면 거기서 읽으면 된다.
@Entity
@Table(name = "assessment_reading")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssessmentReading {

    // 판정과 1:1이라 판정 id를 그대로 PK로 쓴다(별도 시퀀스 없음).
    @Id
    @Column(name = "assessment_id")
    private Long assessmentId;

    // 이 판독이 교정한 직전 판정(확률 있는 것). 변동내역(델타)은 저장하지 않고
    // 조회 때 두 판정의 요인 레벨 diff로 계산한다.
    @Column(name = "base_assessment_id")
    private Long baseAssessmentId;

    // ReadingDraft의 JSON 직렬화 전문. 역직렬화 실패는 판독 없음으로 접는다(조회가 500이 되면 안 된다).
    // MEDIUMTEXT — 원석 통짜와 카드 원문이 함께 실리면서 TEXT(64KB)를 긴 판이 넘었다
    // (한글 3바이트라 실한도 ~2만 자. 생성은 되는데 저장이 터져 간헐 실패로 보이던 원인).
    @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
    private String body;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private AssessmentReading(Long assessmentId, Long baseAssessmentId, String body) {
        this.assessmentId = assessmentId;
        this.baseAssessmentId = baseAssessmentId;
        this.body = body;
    }
}
