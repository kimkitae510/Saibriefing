package com.threeam.assessment.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.threeam.assessment.dto.AssessmentJobResponse;
import com.threeam.assessment.dto.AssessmentResponse;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentJob;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.service.AssessmentJobService;
import com.threeam.assessment.service.AssessmentService;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

// 작업 접수(202)와 작업 조회의 HTTP 모양만 본다. 보안 필터는 끄고 인증 주체(userId)만 컨텍스트에 심는다 —
// JWT 검증은 필터의 몫이고 여기서는 @AuthenticationPrincipal이 Long으로 풀리는지가 관심이다.
@WebMvcTest(controllers = AssessmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class AssessmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AssessmentService assessmentService;

    @MockBean
    private AssessmentJobService jobService;

    private static final AssessmentResponse RESULT = AssessmentResponse.from(
            Assessment.builder().storyId(10L).verdict(ReunionVerdict.POSSIBLE).reason("판독 완료").build());

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST - 작업을 접수하면 202와 작업 상태를 돌려준다")
    void assess_accepted() throws Exception {
        given(jobService.start(1L, 10L)).willReturn(new AssessmentJobResponse(100L,
                AssessmentJob.Status.QUEUED, AssessmentJob.StepStatus.PENDING,
                AssessmentJob.StepStatus.PENDING, null, null));

        mockMvc.perform(post("/api/stories/10/assessments"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(100))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.analysisStatus").value("PENDING"))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    @DisplayName("POST - 사전 가드 안내는 jobId 없이 DONE + result(INSUFFICIENT)로 온다")
    void assess_guide() throws Exception {
        AssessmentResponse guide = AssessmentResponse.from(Assessment.builder().storyId(10L)
                .verdict(ReunionVerdict.INSUFFICIENT).reason("차감되지 않았습니다").build())
                .withRetryAfterSeconds(120);
        given(jobService.start(1L, 10L)).willReturn(AssessmentJobResponse.guide(guide));

        mockMvc.perform(post("/api/stories/10/assessments"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").doesNotExist())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result.verdict").value("INSUFFICIENT"))
                .andExpect(jsonPath("$.result.retryAfterSeconds").value(120));
    }

    @Test
    @DisplayName("POST - 한도 초과는 전역 핸들러가 Q001로 내린다")
    void assess_quotaExceeded() throws Exception {
        given(jobService.start(1L, 10L)).willThrow(new BusinessException(ErrorCode.QUOTA_EXCEEDED));

        mockMvc.perform(post("/api/stories/10/assessments"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("Q001"));
    }

    @Test
    @DisplayName("GET jobs/{id} - DONE이면 result에 판정이 실린다")
    void job_done() throws Exception {
        given(jobService.get(1L, 10L, 100L)).willReturn(new AssessmentJobResponse(100L,
                AssessmentJob.Status.DONE, AssessmentJob.StepStatus.DONE, AssessmentJob.StepStatus.DONE,
                null, RESULT));

        mockMvc.perform(get("/api/stories/10/assessments/jobs/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.result.verdict").value("POSSIBLE"))
                .andExpect(jsonPath("$.result.reason").value("판독 완료"));
    }

    @Test
    @DisplayName("GET jobs/{id} - FAILED면 error가 실리고, 없는 작업이면 404 AS007")
    void job_failedAndMissing() throws Exception {
        given(jobService.get(1L, 10L, 100L)).willReturn(new AssessmentJobResponse(100L,
                AssessmentJob.Status.FAILED, AssessmentJob.StepStatus.DONE, AssessmentJob.StepStatus.FAILED,
                "LlmException", null));
        mockMvc.perform(get("/api/stories/10/assessments/jobs/100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.error").value("LlmException"));

        given(jobService.get(1L, 10L, 101L))
                .willThrow(new BusinessException(ErrorCode.ASSESSMENT_JOB_NOT_FOUND));
        mockMvc.perform(get("/api/stories/10/assessments/jobs/101"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AS007"));
    }

    @Test
    @DisplayName("GET progress - 도는 작업이 있으면 jobId와 단계, 없으면 stage=null")
    void progress() throws Exception {
        given(jobService.progress(1L, 10L)).willReturn(Optional.of(new AssessmentJobResponse(100L,
                AssessmentJob.Status.RUNNING, AssessmentJob.StepStatus.DONE, AssessmentJob.StepStatus.PENDING,
                null, null)));
        mockMvc.perform(get("/api/stories/10/assessments/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("READING"))
                .andExpect(jsonPath("$.jobId").value(100))
                .andExpect(jsonPath("$.analysisStatus").value("DONE"));

        given(jobService.progress(1L, 10L)).willReturn(Optional.empty());
        mockMvc.perform(get("/api/stories/10/assessments/progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").doesNotExist());
    }
}
