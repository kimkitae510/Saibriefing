package com.threeam.match;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.threeam.match.entity.ReunionCase;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 참조 사례의 유일한 저장소. 서비스 자산이라 DB에도 저장소에도 넣지 않고
// 로컬 파일(gitignore)을 서버 시동 때 한 번 읽어 메모리에 상주시킨다.
// 불변 목록이라 캐시 무효화 문제가 없다 — 파일을 고치면 재시작으로 반영한다.
// 파일이 없으면 빈 목록으로 뜬다(매칭만 "사례 없음"이 되고 서비스는 돈다).
@Slf4j
@Component
@RequiredArgsConstructor
public class CaseStore {

    private final ObjectMapper objectMapper;

    // 자산 파일은 빌드 산출물(resources)에 넣지 않는다 — 배포물에 포함돼 새는 경로가 된다.
    @Value("${match.case-file:사례데이터.json}")
    private String caseFile;

    private List<ReunionCase> cases = List.of();

    @PostConstruct
    void load() {
        Path path = Path.of(caseFile);
        if (!Files.exists(path)) {
            log.error("사례 파일 없음: {} — 비슷한 사례가 항상 비어 나감", path.toAbsolutePath());
            return;
        }
        try {
            // 세터 없는 자산 객체라 필드로 바인딩한다(전역 매퍼 설정은 건드리지 않게 복사본으로).
            ObjectMapper mapper = objectMapper.copy()
                    .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
            JsonNode root = mapper.readTree(Files.readString(path));
            List<ReunionCase> loaded = new ArrayList<>();
            for (JsonNode node : root.path("cases")) {
                ReunionCase item = mapper.treeToValue(node, ReunionCase.class);
                // 파일엔 id가 없다 — 배열 순서를 id로 쓴다. 순서가 곧 동점 정렬 기준이라
                // 사례를 중간에 끼워 넣으면 동점 순서가 바뀔 수 있음을 감수한다(맨 뒤 추가 권장).
                item.assignId((long) loaded.size());
                loaded.add(item);
            }
            cases = List.copyOf(loaded);
            log.info("사례 {}건 로드: {}", cases.size(), path.toAbsolutePath());
        } catch (Exception e) {
            log.error("사례 파일 파싱 실패: {} — 비슷한 사례가 항상 비어 나감", path.toAbsolutePath(), e);
        }
    }

    public List<ReunionCase> all() {
        return cases;
    }

    // id는 로드 시 배열 순서로 부여하므로 인덱스와 같다. 저장된 선택을 다시 그릴 때 쓴다 —
    // 사례를 중간에 끼워 넣으면 옛 선택이 다른 사례를 가리키므로 추가는 맨 뒤에만 한다(load 참고).
    public ReunionCase byId(Long id) {
        if (id == null || id < 0 || id >= cases.size()) {
            return null;
        }
        return cases.get(id.intValue());
    }
}
