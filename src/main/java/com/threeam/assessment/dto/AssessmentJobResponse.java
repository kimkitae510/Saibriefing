package com.threeam.assessment.dto;

import com.threeam.assessment.entity.AssessmentJob;

// 분석 작업의 진행 상태. result는 DONE일 때만 — 옛 POST가 돌려주던 것과 같은 판정 응답이다.
// jobId가 null이면 LLM 없이 사전 가드에서 끝난 판(발화 없음, 근거부족 재시도, 실패 쿨다운) — result에 안내가 실린다.
public record AssessmentJobResponse(Long jobId, AssessmentJob.Status status,
                                    AssessmentJob.StepStatus analysisStatus,
                                    AssessmentJob.StepStatus judgementStatus,
                                    String error, AssessmentResponse result) {

    public static AssessmentJobResponse of(AssessmentJob job, AssessmentResponse result) {
        return new AssessmentJobResponse(job.getId(), job.getStatus(), job.getAnalysisStatus(),
                job.getJudgementStatus(), job.getError(), result);
    }

    public static AssessmentJobResponse guide(AssessmentResponse guide) {
        return new AssessmentJobResponse(null, AssessmentJob.Status.DONE, AssessmentJob.StepStatus.DONE,
                AssessmentJob.StepStatus.DONE, null, guide);
    }
}
