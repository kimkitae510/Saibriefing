package com.threeam.assessment.controller;

import com.threeam.assessment.dto.AssessmentJobResponse;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.entity.AssessmentJob;
import com.threeam.assessment.service.AssessmentJobService;
import com.threeam.assessment.service.AssessmentService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stories/{storyId}/assessments")
@RequiredArgsConstructor
public class AssessmentController {

    private final AssessmentService assessmentService;
    private final AssessmentJobService jobService;

    // 분석 작업을 접수한다(202). 결과는 작업 폴링으로 — 두 LLM 호출이 분 단위라 연결을 붙들지 않는다.
    // 이미 도는 작업이 있으면 그 작업을, 사전 가드에서 끝나면 jobId 없이 안내(result)를 돌려준다.
    @PostMapping
    public ResponseEntity<AssessmentJobResponse> assess(@AuthenticationPrincipal Long userId,
                                                        @PathVariable Long storyId) {
        return ResponseEntity.accepted().body(jobService.start(userId, storyId));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<AssessmentJobResponse> job(@AuthenticationPrincipal Long userId,
                                                     @PathVariable Long storyId,
                                                     @PathVariable Long jobId) {
        return ResponseEntity.ok(jobService.get(userId, storyId, jobId));
    }

    @GetMapping
    public ResponseEntity<List<AssessmentResponse>> getHistory(@AuthenticationPrincipal Long userId,
                                                               @PathVariable Long storyId) {
        return ResponseEntity.ok(assessmentService.getHistory(userId, storyId));
    }

    // 진행 중인 작업이 있는지 — 화면이 재진입 때 붙을 작업을 찾는 데 쓴다. 없으면 stage=null.
    @GetMapping("/progress")
    public ResponseEntity<ProgressResponse> progress(@AuthenticationPrincipal Long userId,
                                                     @PathVariable Long storyId) {
        return ResponseEntity.ok(jobService.progress(userId, storyId)
                .map(job -> new ProgressResponse("READING", job.jobId(), job.analysisStatus(),
                        job.judgementStatus()))
                .orElseGet(() -> new ProgressResponse(null, null, null, null)));
    }

    public record ProgressResponse(String stage, Long jobId, AssessmentJob.StepStatus analysisStatus,
                                   AssessmentJob.StepStatus judgementStatus) {
    }

    // "만나는 중" 잠금을 유저가 직접 번복한다(분석이 오해했을 수 있다). 오판이던 잠금 판정을
    // 지우고 직전 확률 분석을 돌려준다 — 직전 분석이 없으면 204(첫 분석 안내로 복귀).
    @PostMapping("/confirm-breakup")
    public ResponseEntity<AssessmentResponse> confirmBreakup(@AuthenticationPrincipal Long userId,
                                                             @PathVariable Long storyId) {
        return assessmentService.confirmBreakup(userId, storyId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    // "상대의 재회 제안 유효(100%)" 확정을 유저가 직접 번복한다(제안이 아니었거나 없던 일이 됨).
    // 원장에 정정을 남기고, 저장된 신호의 재합산 값으로 즉시 되돌린 결과를 돌려준다.
    @PostMapping("/retract-offer")
    public ResponseEntity<AssessmentResponse> retractOffer(@AuthenticationPrincipal Long userId,
                                                           @PathVariable Long storyId) {
        return ResponseEntity.ok(assessmentService.retractOffer(userId, storyId));
    }
}
