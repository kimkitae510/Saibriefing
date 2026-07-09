package com.threeam.match.service;

import com.threeam.assessment.service.AssessmentTxService;
import com.threeam.llm.ChatMessage;
import com.threeam.match.CaseStore;
import com.threeam.match.MatchBand;
import com.threeam.match.dto.PickedCaseResponse;
import com.threeam.match.dto.PickedCasesResponse;
import com.threeam.match.entity.MatchPick;
import com.threeam.match.entity.ReunionCase;
import com.threeam.match.entity.StoryMatchProfile;
import com.threeam.usage.UsageKind;
import com.threeam.usage.UsageLimiter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 유료 사례 매칭. 무료 매칭(MatchService)과 갈라둔 이유는 값을 만드는 지점이 다르기 때문이다 —
// 무료는 태그 겹침만 보고 상위 2건을 결정적으로 계산하고, 여기는 LLM이 본문을 읽고 고른다.
//
// 순서가 이 클래스의 전부다: 저장이 끝난 뒤에 쿼터를 깎는다. 중간에 서버가 죽으면 저장이 없으니
// 쿼터도 안 깎여서 유저가 그대로 다시 돌릴 수 있다. 반대로 하면 돈만 나가고 결과가 없는 판이 생긴다.
@Slf4j
@Service
@RequiredArgsConstructor
public class PaidMatchService {

    private final AssessmentTxService assessmentTxService;
    private final PaidMatchTxService txService;
    private final CaseCandidates candidates;
    private final CaseStore caseStore;
    private final CaseScorer scorer;
    private final MatchLlm matchLlm;
    private final UsageLimiter usageLimiter;
    private final Executor llmCallbackExecutor;

    // 이미 돌린 결과가 있는지. 쿼터도 락도 안 건드린다 — 재조회는 공짜여야 한다.
    public PickedCasesResponse peek(Long userId, Long storyId) {
        return txService.findSaved(userId, storyId)
                .map(saved -> toResponse(saved.picks(), saved.profile()))
                .orElse(PickedCasesResponse.notRun());
    }

    public CompletableFuture<PickedCasesResponse> pick(Long userId, Long storyId) {
        // 저장된 게 있으면 그대로 준다. 새로고침마다 사례가 바뀌면 유료 화면이 아니라 뽑기가 된다.
        var saved = txService.findSaved(userId, storyId);
        if (saved.isPresent()) {
            return CompletableFuture.completedFuture(
                    toResponse(saved.get().picks(), saved.get().profile()));
        }

        usageLimiter.acquireInFlight(UsageKind.MATCH, userId);
        try {
            // 후차감: 여기서는 한도 검사만. 차감은 저장이 끝난 뒤에 한다.
            usageLimiter.check(UsageKind.MATCH, userId, 1);

            PaidMatchTxService.Material material = txService.loadMaterial(userId, storyId);
            StoryMatchProfile profile = material.profile();
            if (profile == null || !profile.matchable()) {
                // LLM을 안 부르므로 쿼터도 안 깎는다. 대화가 더 쌓이면 열린다.
                usageLimiter.releaseInFlight(UsageKind.MATCH, userId);
                return CompletableFuture.completedFuture(PickedCasesResponse.noProfile());
            }

            MatchBand band = MatchBand.of(material.probability());
            CaseCandidates.Pool pool = candidates.of(profile, band);
            if (pool.isEmpty()) {
                // 하한을 넘는 후보가 없다. 후보를 채우려고 하한을 풀지 않는다 — 그러면
                // "닮았다고 말하기 민망한" 것 중에서 고르게 되고 앞의 카드까지 못 믿게 된다.
                usageLimiter.releaseInFlight(UsageKind.MATCH, userId);
                return CompletableFuture.completedFuture(PickedCasesResponse.of(List.of(), null));
            }

            // loadContext가 아니라 이걸 쓴다 — loadContext에는 재분석 가드가 들어 있어서
            // "마지막 분석 이후 새 이야기가 없다"로 매칭까지 막혔다(실측). 매칭은 이미 나온
            // 진단에 붙는 일이라 새 대화를 요구할 이유가 없다.
            List<ChatMessage> conversation = assessmentTxService.loadConversation(userId, storyId);
            return matchLlm.select(material.digest(), conversation, pool, band)
                    .thenApplyAsync(picked -> {
                        MatchPick.Picks settled = settle(picked, pool, band);
                        // 내가 실제로 저장한 경우에만 깎는다. 이미 다른 요청이 넣어둔 결과라면
                        // 그쪽이 이미 값을 치렀으므로 여기서 또 깎으면 이중 차감이 된다.
                        if (txService.save(material.assessmentId(), storyId, userId, settled)) {
                            recordUsageQuietly(userId);
                        } else {
                            log.warn("이미 저장된 매칭 — 차감 없이 반환한다 assessmentId={}",
                                    material.assessmentId());
                        }
                        return toResponse(settled, profile);
                    }, llmCallbackExecutor)
                    .whenComplete((ignored, ex) -> {
                        if (ex != null) {
                            // LLM 실패는 폴백하지 않고 그대로 던진다. 점수 순 상위로 대신 채우면
                            // 유저는 유료 결과를 받은 줄 알지만 실제로는 무료 매칭과 같은 것을 산 게 된다.
                            // 미차감이라 다시 누르면 된다.
                            log.error("유료 매칭 실패 storyId={} userId={}", storyId, userId, ex);
                        }
                        usageLimiter.releaseInFlight(UsageKind.MATCH, userId);
                    });
        } catch (RuntimeException e) {
            // 후차감이라 되돌릴 차감이 없다. 잠금만 풀고 그대로 던진다.
            usageLimiter.releaseInFlight(UsageKind.MATCH, userId);
            throw e;
        }
    }

    // LLM 응답을 이번 판의 규칙에 맞춘다. 스키마가 caseId는 못 박아 주지만 구성(성공 몇, 실패 몇)은
    // 표현할 수 없어서 여기서 자른다. 상한을 넘으면 앞에서부터 남기고, 하나도 안 남으면
    // 각 버킷의 최상위로 채운다 — 유료 화면이 빈 채로 나가는 것보다는 낫다.
    MatchPick.Picks settle(MatchPick.Picks picked, CaseCandidates.Pool pool, MatchBand band) {
        List<MatchPick> kept = new ArrayList<>();
        int successes = 0;
        int failures = 0;
        for (MatchPick pick : picked.items()) {
            ReunionCase target = find(pool, pick.caseId());
            if (target == null || kept.stream().anyMatch(k -> Objects.equals(k.caseId(), pick.caseId()))) {
                continue;
            }
            boolean success = "성공".equals(target.getOutcome());
            if (success ? successes >= band.successMax() : failures >= band.failMax()) {
                continue;
            }
            kept.add(pick);
            if (success) {
                successes++;
            } else {
                failures++;
            }
        }
        if (!kept.isEmpty()) {
            // 카드가 한 장뿐이면 견줄 게 없다 — 요약을 비워 화면이 그 줄을 안 그리게 한다.
            return new MatchPick.Picks(kept.size() > 1 ? picked.summary() : null, kept);
        }
        // 해설 없이 사례만 남는다 — 왜 닮았는지는 지어내느니 비워 두는 게 낫다.
        // 요약도 비운다: 버려진 카드를 설명하는 문장이 남으면 화면과 어긋난다.
        log.warn("매칭 선택이 전부 무효 — 점수 순 상위로 대체한다 응답수={}", picked.items().size());
        List<MatchPick> fallback = new ArrayList<>();
        pool.successes().stream().limit(band.successMax())
                .forEach(target -> fallback.add(new MatchPick(target.getId(), null, null, null)));
        pool.failures().stream().limit(band.failMax())
                .forEach(target -> fallback.add(new MatchPick(target.getId(), null, null, null)));
        return new MatchPick.Picks(null, fallback);
    }

    private ReunionCase find(CaseCandidates.Pool pool, Long caseId) {
        return pool.all().stream()
                .filter(target -> Objects.equals(target.getId(), caseId))
                .findFirst()
                .orElse(null);
    }

    // 저장된 선택을 화면 카드로 되살린다. 사례 본문과 겹침 태그는 저장하지 않는다 —
    // 사례 파일과 가중치는 고쳐 나갈 값이라, 박제해 두면 옛 태그가 새 규칙과 어긋난 채 남는다.
    private PickedCasesResponse toResponse(MatchPick.Picks picked, StoryMatchProfile profile) {
        List<PickedCaseResponse> cases = picked.items().stream()
                .map(pick -> {
                    ReunionCase target = caseStore.byId(pick.caseId());
                    if (target == null) {
                        // 사례 파일이 줄어 id가 비었다. 카드 한 장을 빼고 나머지는 그대로 보여준다.
                        log.warn("저장된 매칭이 가리키는 사례 없음 caseId={}", pick.caseId());
                        return null;
                    }
                    List<String> tags = safeTags(pick.tags());
                    if (tags.isEmpty() && profile != null) {
                        // 폴백으로 채운 판이라 해설이 없다 — 규칙 태그로 카드를 비어 보이지 않게 한다.
                        tags = scorer.matchedTags(profile, target);
                    }
                    return PickedCaseResponse.from(target, tags, pick.similarity(), pick.reading());
                })
                .filter(Objects::nonNull)
                .toList();
        return PickedCasesResponse.of(cases, picked.summary());
    }

    // 태그는 프롬프트가 1차로 거른다(적나라한 것은 만들지 말라고 지시). 여기는 최후 방어선이다 —
    // 프롬프트는 대부분 지켜지지만 "대부분"이 남의 카드에 #폭력이 박히는 사고를 막아주지는 않는다.
    // 목록을 넓히지 않는 게 중요하다: 넓히면 LLM에게 태그를 맡긴 뜻이 사라진다.
    private static final List<String> BLOCKED = List.of(
            "폭력", "폭행", "때리", "때림", "맞았", "손찌검", "가스라이팅", "성적", "도박");

    // 4개까지만 — 카드 하나가 태그밭이 되면 무엇이 진짜 겹침인지 흐려진다(규칙 시절과 같은 상한).
    private static final int MAX_TAGS = 4;

    private List<String> safeTags(List<String> tags) {
        return tags.stream()
                .filter(tag -> {
                    boolean blocked = BLOCKED.stream().anyMatch(tag::contains);
                    if (blocked) {
                        log.warn("적나라한 태그를 걸렀다 tag={}", tag);
                    }
                    return !blocked;
                })
                .limit(MAX_TAGS)
                .toList();
    }

    // 차감 실패가 결과 반환을 막으면 안 된다 — 이미 저장된 결과는 유저 것이다.
    private void recordUsageQuietly(Long userId) {
        try {
            usageLimiter.record(UsageKind.MATCH, userId, 1);
        } catch (RuntimeException e) {
            log.error("매칭 쿼터 차감 실패 userId={}", userId, e);
        }
    }
}
