package com.threeam.assessment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.threeam.assessment.dto.RelationshipPsychology;
import com.threeam.assessment.dto.ShareCreateResponse;
import com.threeam.assessment.dto.SharedAssessmentResponse;
import com.threeam.assessment.entity.Assessment;
import com.threeam.assessment.entity.AssessmentFactor;
import com.threeam.assessment.entity.AssessmentShare;
import com.threeam.assessment.entity.FactorLevel;
import com.threeam.assessment.entity.FactorName;
import com.threeam.assessment.entity.RelapseRisk;
import com.threeam.assessment.entity.ReunionVerdict;
import com.threeam.assessment.repository.AssessmentRepository;
import com.threeam.assessment.repository.AssessmentShareRepository;
import com.threeam.global.exception.ErrorCode;
import com.threeam.global.exception.custom.BusinessException;
import com.threeam.story.entity.Story;
import com.threeam.story.repository.StoryRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AssessmentShareServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long STORY_ID = 10L;

    @Mock
    private StoryRepository storyRepository;

    @Mock
    private AssessmentRepository assessmentRepository;

    @Mock
    private AssessmentShareRepository shareRepository;

    @InjectMocks
    private AssessmentShareService service;

    private Assessment assessment(Long id, Integer probability) {
        Assessment assessment = Assessment.builder()
                .storyId(STORY_ID)
                .verdict(probability != null ? ReunionVerdict.POSSIBLE : ReunionVerdict.REUNITED)
                .probability(probability)
                .typeEvidence("유형 근거")
                .relapseRisk(RelapseRisk.HIGH)
                .relapseReason("재발 이유")
                .relationshipPsychology(new RelationshipPsychology(
                        new RelationshipPsychology.Attachment(
                                new RelationshipPsychology.Style("불안형", "중간"),
                                new RelationshipPsychology.Style("회피형", "중간"),
                                "설명"),
                        null, null))
                .reason("총평")
                .factor(AssessmentFactor.of(FactorName.PARTNER_SIGNAL, FactorLevel.FAVORABLE,
                        "연락이 먼저 왔다", "판독", null))
                .factor(AssessmentFactor.of(FactorName.CONTACT_PATH, FactorLevel.NEUTRAL,
                        "근거 없음", null, null))
                .build();
        ReflectionTestUtils.setField(assessment, "id", id);
        return assessment;
    }

    private void givenOwnedStory() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.of(mock(Story.class)));
    }

    @Test
    @DisplayName("생성: 남의 방이거나 삭제된 방이면 S001")
    void create_storyNotOwned() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_ID, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STORY_NOT_FOUND);
    }

    @Test
    @DisplayName("생성: 진단 기록이 없으면 SH002")
    void create_noAssessment() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_ID, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHARE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("생성: 잠금 판정(확률 없음)은 공유 대상이 아니다 — SH002")
    void create_lockedVerdict() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(assessment(5L, null)));

        assertThatThrownBy(() -> service.create(USER_ID, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHARE_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("생성: 같은 진단에 이미 토큰이 있으면 재사용하고 새로 저장하지 않는다")
    void create_reusesExistingToken() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(assessment(5L, 62)));
        given(shareRepository.findFirstByAssessmentIdAndRevokedAtIsNull(5L))
                .willReturn(Optional.of(AssessmentShare.of(5L, STORY_ID, USER_ID, "existing-token")));

        ShareCreateResponse response = service.create(USER_ID, STORY_ID);

        assertThat(response.getToken()).isEqualTo("existing-token");
        verify(shareRepository, never()).save(any());
    }

    @Test
    @DisplayName("생성: 첫 공유면 랜덤 토큰(base64url 32자)으로 저장한다")
    void create_savesNewToken() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(assessment(5L, 62)));
        given(shareRepository.findFirstByAssessmentIdAndRevokedAtIsNull(5L)).willReturn(Optional.empty());
        given(shareRepository.save(any(AssessmentShare.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ShareCreateResponse response = service.create(USER_ID, STORY_ID);

        assertThat(response.getToken()).matches("[A-Za-z0-9_-]{32}");
    }

    @Test
    @DisplayName("공개 조회: 없는 토큰이면 SH001")
    void getShared_unknownToken() {
        given(shareRepository.findByToken("nope")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getShared("nope"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHARE_NOT_FOUND);
    }

    @Test
    @DisplayName("공개 조회: 방이 삭제됐으면 링크도 죽는다 — SH001")
    void getShared_deletedStory() {
        given(shareRepository.findByToken("tok"))
                .willReturn(Optional.of(AssessmentShare.of(5L, STORY_ID, USER_ID, "tok")));
        given(storyRepository.findByIdAndDeletedAtIsNull(STORY_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getShared("tok"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHARE_NOT_FOUND);
    }

    @Test
    @DisplayName("공개 조회: 본인 화면과 같은 판독을 내린다 — 근거 문장과 해석층까지")
    void getShared_sameReadingAsOwner() {
        given(shareRepository.findByToken("tok"))
                .willReturn(Optional.of(AssessmentShare.of(5L, STORY_ID, USER_ID, "tok")));
        given(storyRepository.findByIdAndDeletedAtIsNull(STORY_ID))
                .willReturn(Optional.of(mock(Story.class)));
        given(assessmentRepository.findById(5L)).willReturn(Optional.of(assessment(5L, 62)));

        SharedAssessmentResponse response = service.getShared("tok");

        assertThat(response.getProbability()).isEqualTo(62);
        assertThat(response.getReason()).isEqualTo("총평");
        assertThat(response.getTypeEvidence()).isEqualTo("유형 근거");
        assertThat(response.getRelapseRisk()).isEqualTo("높음");
        assertThat(response.getRelapseReason()).isEqualTo("재발 이유");
        assertThat(response.getRelationshipPsychology()).isNotNull();
        assertThat(response.getFactors().get(0).getEvidence()).isEqualTo("연락이 먼저 왔다");
        assertThat(response.getFactors().get(0).getRationale()).isEqualTo("판독");
    }

    @Test
    @DisplayName("공개 조회: 판단 안 된 중립 요인은 걸러진다 — 남에겐 정보가 아니다")
    void getShared_dropsNeutralFactors() {
        given(shareRepository.findByToken("tok"))
                .willReturn(Optional.of(AssessmentShare.of(5L, STORY_ID, USER_ID, "tok")));
        given(storyRepository.findByIdAndDeletedAtIsNull(STORY_ID))
                .willReturn(Optional.of(mock(Story.class)));
        given(assessmentRepository.findById(5L)).willReturn(Optional.of(assessment(5L, 62)));

        SharedAssessmentResponse response = service.getShared("tok");

        assertThat(response.getFactors()).hasSize(1);
        assertThat(response.getFactors().get(0).getName()).isEqualTo(FactorName.PARTNER_SIGNAL.label());
        assertThat(response.getFactors().get(0).getLevel()).isEqualTo("유리");
    }

    @Test
    @DisplayName("취소: 이 사연의 살아 있는 링크를 전부 끈다 — 옛 분석의 링크도 함께")
    void revoke_killsEveryLiveLinkOfStory() {
        givenOwnedStory();
        AssessmentShare latest = AssessmentShare.of(5L, STORY_ID, USER_ID, "latest-token");
        AssessmentShare older = AssessmentShare.of(4L, STORY_ID, USER_ID, "older-token");
        given(shareRepository.findByStoryIdAndRevokedAtIsNull(STORY_ID))
                .willReturn(List.of(latest, older));

        service.revoke(USER_ID, STORY_ID);

        assertThat(latest.isRevoked()).isTrue();
        assertThat(older.isRevoked()).isTrue();
    }

    @Test
    @DisplayName("취소: 남의 사연은 끄지 못한다")
    void revoke_rejectsOtherUsersStory() {
        given(storyRepository.findByIdAndUserIdAndDeletedAtIsNull(STORY_ID, USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(USER_ID, STORY_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.STORY_NOT_FOUND);
    }

    @Test
    @DisplayName("공개 조회: 취소된 토큰은 없는 것과 같다 — SH001")
    void getShared_revokedToken() {
        AssessmentShare revoked = AssessmentShare.of(5L, STORY_ID, USER_ID, "dead-token");
        revoked.revoke();
        given(shareRepository.findByToken("dead-token")).willReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.getShared("dead-token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SHARE_NOT_FOUND);
    }

    @Test
    @DisplayName("생성: 취소한 뒤 다시 공유하면 죽은 토큰이 아니라 새 토큰이 나간다")
    void create_afterRevokeIssuesNewToken() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(assessment(5L, 62)));
        // 취소된 행은 무덤으로 남아 이 조회에 안 잡힌다
        given(shareRepository.findFirstByAssessmentIdAndRevokedAtIsNull(5L)).willReturn(Optional.empty());
        given(shareRepository.save(any(AssessmentShare.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        ShareCreateResponse response = service.create(USER_ID, STORY_ID);

        assertThat(response.getToken()).matches("[A-Za-z0-9_-]{32}");
        assertThat(response.getToken()).isNotEqualTo("dead-token");
    }

    @Test
    @DisplayName("상태: 살아 있는 링크가 없으면 null을 돌려준다")
    void activeToken_noneWhenNotShared() {
        givenOwnedStory();
        given(assessmentRepository.findFirstByStoryIdOrderByCreatedAtDesc(STORY_ID))
                .willReturn(Optional.of(assessment(5L, 62)));
        given(shareRepository.findFirstByAssessmentIdAndRevokedAtIsNull(5L)).willReturn(Optional.empty());

        assertThat(service.activeToken(USER_ID, STORY_ID)).isNull();
    }
}
