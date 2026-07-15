package com.threeam.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

// 분석 작업 한 건 — 분석(A)과 재회 판독(B) 두 LLM 호출의 진행 상태와 각 산출 텍스트를 DB에 둔다.
// 화면이 끊기거나 서버가 재시작해도 끝난 쪽 텍스트는 남아, 재시도가 그 호출을 다시 사지 않는다.
// 결과 자체는 assessments/assessment_readings에 있고 여기는 그 id만 가리킨다.
@Entity
@Table(name = "assessment_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AssessmentJob {

    public enum Status { QUEUED, RUNNING, DONE, FAILED }

    public enum StepStatus { PENDING, DONE, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storyId;

    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private StepStatus analysisStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private StepStatus judgementStatus;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String analysisText;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String judgementText;

    @Column(length = 500)
    private String error;

    // 게이트가 근거부족으로 돌려보낸 판의 안내문 — 판정 행이 없으므로 결과를 여기서 되살린다.
    @Column(length = 500)
    private String note;

    private Long assessmentId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime finishedAt;

    // 지난 실패 작업에서 끝난 쪽 텍스트를 받아 시작하면 그 단계는 처음부터 DONE이다.
    // judgementOn=false면 B가 없는 판이라 판독 단계도 DONE으로 두고 지나간다.
    @Builder
    private AssessmentJob(Long storyId, Long userId, String analysisText, String judgementText,
                          boolean judgementOn) {
        this.storyId = storyId;
        this.userId = userId;
        this.status = Status.QUEUED;
        this.analysisText = blankToNull(analysisText);
        this.analysisStatus = this.analysisText == null ? StepStatus.PENDING : StepStatus.DONE;
        this.judgementText = blankToNull(judgementText);
        this.judgementStatus = !judgementOn || this.judgementText != null ? StepStatus.DONE : StepStatus.PENDING;
    }

    public boolean isActive() {
        return status == Status.QUEUED || status == Status.RUNNING;
    }

    public boolean isAnalysisDone() {
        return analysisStatus == StepStatus.DONE;
    }

    public boolean isJudgementDone() {
        return judgementStatus == StepStatus.DONE;
    }

    public void start() {
        this.status = Status.RUNNING;
    }

    public void completeAnalysis(String text) {
        this.analysisText = text;
        this.analysisStatus = StepStatus.DONE;
    }

    public void completeJudgement(String text) {
        this.judgementText = text;
        this.judgementStatus = StepStatus.DONE;
    }

    public void finish(Long assessmentId, String note) {
        this.status = Status.DONE;
        this.assessmentId = assessmentId;
        this.note = clip(note);
        this.finishedAt = LocalDateTime.now();
    }

    // 아직 안 끝난 단계만 FAILED로 — 끝난 쪽 텍스트와 DONE은 그대로 남겨 재시도가 이어 쓴다.
    public void fail(String error) {
        this.status = Status.FAILED;
        this.error = clip(error);
        if (analysisStatus == StepStatus.PENDING) {
            analysisStatus = StepStatus.FAILED;
        }
        if (judgementStatus == StepStatus.PENDING) {
            judgementStatus = StepStatus.FAILED;
        }
        this.finishedAt = LocalDateTime.now();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String clip(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 500 ? s : s.substring(0, 500);
    }
}
