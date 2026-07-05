package com.threeam.assessment.controller;

import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.service.AssessmentService;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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

    // 사연의 대화를 읽어 분석한다. LLM 호출이 끼므로 논블로킹으로 반환한다.
    @PostMapping
    public CompletableFuture<ResponseEntity<AssessmentResponse>> assess(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long storyId) {
        return assessmentService.assess(userId, storyId)
                .thenApply(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    @GetMapping
    public ResponseEntity<List<AssessmentResponse>> getHistory(@AuthenticationPrincipal Long userId,
                                                               @PathVariable Long storyId) {
        return ResponseEntity.ok(assessmentService.getHistory(userId, storyId));
    }

    // 분석이 도는 동안 화면이 폴링한다 — 어느 단계(판정/판독)인지. 진행 중이 아니면 stage=null.
    @GetMapping("/progress")
    public ResponseEntity<ProgressResponse> progress(@AuthenticationPrincipal Long userId,
                                                     @PathVariable Long storyId) {
        return ResponseEntity.ok(new ProgressResponse(assessmentService.progressStage(userId, storyId)));
    }

    public record ProgressResponse(String stage) {
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
