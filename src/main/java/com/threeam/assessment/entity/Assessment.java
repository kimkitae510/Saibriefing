package com.threeam.assessment.entity;

import com.threeam.assessment.dto.RelationshipPsychology;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Singular;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 한 시점의 분석 리포트 결과. 사연(storyId)별로 쌓여 시간에 따른 확률 변화 히스토리가 된다.
// v2: 자유 감점(deductions) 대신 유형(1층) + 고정 요인 판정(2층)을 남긴다 —
// 확률은 TypeBandScorer가 이 둘로 계산하므로, 제안 번복(retract-offer) 때 재분석 없이 재현된다.
@Entity
@Table(name = "assessments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Assessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long storyId;

    // Hibernate 6.x가 STRING enum을 MySQL 네이티브 ENUM으로 매핑하는 걸 막고 varchar로 고정.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private ReunionVerdict verdict;

    // 잠금 판정(DATING, REUNITED, NOT_ADVISABLE)일 땐 null.
    @Column
    private Integer probability;

    // 이별 유형(1층 — 대역을 정한다). POSSIBLE에만 있고 잠금 판정은 null.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 20)
    private BreakupType breakupType;

    // 유형 판정 근거 한 줄 — 화면의 유형 배지가 "왜 이 유형?"에 답하는 재료.
    @Column(length = 300)
    private String typeEvidence;

    // 점프 규칙(유형 대역을 통째로 교체하는 판) — 재계산 재료라 판정 자체를 저장한다.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 30)
    private JumpRule jumpRule;

    // 유지 전망 — 성사 확률과 별개 축.
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 10)
    private RelapseRisk relapseRisk;

    @Column(length = 300)
    private String relapseReason;

    // 관계 심리(애착 경향, 관계 패턴, 욕구 충돌) — 확률과 무관한 "관계 이해용" 층.
    // JSON 통짜 저장(컨버터 참고). 정보가 부족한 진단은 null.
    @Convert(converter = RelationshipPsychologyConverter.class)
    @Column(columnDefinition = "TEXT")
    private RelationshipPsychology relationshipPsychology;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    // 요인 판정(2층). 별도 테이블(assessment_factors)에 쌓인다.
    @ElementCollection
    @CollectionTable(name = "assessment_factors", joinColumns = @JoinColumn(name = "assessment_id"))
    @BatchSize(size = 100)
    private List<AssessmentFactor> factors = new ArrayList<>();

    // 관찰 포인트 — "이게 확인되면 판이 바뀐다". 별도 테이블(assessment_watch).
    @ElementCollection
    @CollectionTable(name = "assessment_watch", joinColumns = @JoinColumn(name = "assessment_id"))
    @BatchSize(size = 100)
    private List<WatchPoint> watchPoints = new ArrayList<>();

    // 상담자가 물었는데 유저가 답하지 않은 것. 화면의 "아직 모르는 것"이 요인 슬롯의 고정 문구
    // 대신 이걸 쓴다 — 그 사연을 읽고 만든 질문이라 훨씬 구체적이다.
    @ElementCollection
    @CollectionTable(name = "assessment_unanswered",
            joinColumns = @JoinColumn(name = "assessment_id"))
    @Column(name = "question", nullable = false, length = 300)
    @BatchSize(size = 100)
    private List<String> unansweredQuestions = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private Assessment(Long storyId, ReunionVerdict verdict, Integer probability,
                       BreakupType breakupType,
                       String typeEvidence, JumpRule jumpRule,
                       RelapseRisk relapseRisk, String relapseReason,
                       RelationshipPsychology relationshipPsychology, String reason,
                       @Singular List<AssessmentFactor> factors,
                       @Singular List<WatchPoint> watchPoints,
                       @Singular("unansweredQuestion") List<String> unansweredQuestions) {
        this.storyId = storyId;
        this.verdict = verdict;
        this.probability = probability;
        this.breakupType = breakupType;
        this.typeEvidence = typeEvidence;
        this.jumpRule = jumpRule;
        this.relapseRisk = relapseRisk;
        this.relapseReason = relapseReason;
        this.relationshipPsychology = relationshipPsychology;
        this.reason = reason;
        this.factors = factors != null ? new ArrayList<>(factors) : new ArrayList<>();
        this.watchPoints = watchPoints != null ? new ArrayList<>(watchPoints) : new ArrayList<>();
        this.unansweredQuestions = unansweredQuestions != null
                ? new ArrayList<>(unansweredQuestions) : new ArrayList<>();
    }

    // 상대 제안 확정(100)을 유저가 번복할 때. 새 파이프라인 판은 유형/요인이 없어 재계산
    // 대역이 없다 — null로 되돌려 재분석(판독+결정)을 유도한다(원장 정정과 세트).
    public void retractOffer(Integer recalculatedProbability) {
        this.probability = recalculatedProbability;
    }
}
