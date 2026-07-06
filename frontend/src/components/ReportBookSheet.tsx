import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ReadingBook } from './ReadingBook';
import { getAssessments, type AssessmentResponse } from '../api/assessment';
import { BRAND } from '../brand';
import styles from './ReportBookSheet.module.css';

// 채팅의 "분석 리포트가 도착했습니다" 카드가 여는 판독 뷰어.
// 닫힌 표지가 먼저 서고, 누르면 표지가 넘어가며 안의 판독(ReadingBook)이 펼쳐진다.
// 새 분석을 만들지 않는다 — 저장된 최신 분석(공짜 GET)만 읽는다. 아직 분석이 없는 방은
// 표지를 넘기면 준비 안내 장이 나오고, 생성은 기존 분석 화면(질문 시트 포함)이 맡는다.
export function ReportBookSheet({
  storyId,
  onClose,
}: {
  storyId: number;
  onClose: () => void;
}) {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [latest, setLatest] = useState<AssessmentResponse | null>(null);
  const [history, setHistory] = useState<number[]>([]);
  const [opened, setOpened] = useState(false);
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    getAssessments(storyId)
      .then((all) => {
        if (!aliveRef.current) return;
        // 잠금 판정(REUNITED 등)은 판독이 없다 — 판독이 있는 가장 최근 분석을 편다
        setLatest(all.find((a) => a.reading != null) ?? null);
        // 추세는 분석 화면과 같은 계산 — 확률 있는 분석을 과거순으로, 마지막 다섯 개만
        setHistory(
          all
            .filter((a) => a.probability != null)
            .map((a) => a.probability as number)
            .reverse()
            .slice(-5),
        );
      })
      .catch(() => aliveRef.current && setFailed(true))
      .finally(() => aliveRef.current && setLoading(false));
    return () => {
      aliveRef.current = false;
    };
  }, [storyId]);

  const reading = latest?.reading ?? null;

  return (
    <div className={styles.overlay}>
      <button className={styles.close} onClick={onClose} aria-label="닫기">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M6 6l12 12M18 6L6 18"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
          />
        </svg>
      </button>

      {/* 표지와 본문을 한 틀에 두고 열림을 클래스로만 가른다 — 표지가 넘어가는 동안
          본문이 같은 자리에서 드러나야 "책이 열렸다"로 읽힌다(화면 전환이 아니라) */}
      <div className={`${styles.book} ${opened ? styles.bookOpen : ''}`}>
        <div className={styles.page} aria-hidden={!opened}>
          {opened &&
            (reading ? (
              <ReadingBook
                reading={reading}
                probability={latest?.probability ?? null}
                history={history}
              />
            ) : (
              // 아직 분석이 없거나(준비 안내) 조회가 실패한 판 — 빈 책이 아니라 첫 장이 말한다
              <div className={styles.empty}>
                <div className={styles.emptyTitle}>
                  {failed ? '리포트를 불러오지 못했습니다' : '리포트가 아직 준비되지 않았습니다'}
                </div>
                <div className={styles.emptyBody}>
                  {failed
                    ? '잠시 후 다시 열어 주세요.'
                    : '분석을 시작하면 지금까지의 대화를 근거로 이 책에 판독을 담아 드립니다.'}
                </div>
                {!failed && (
                  <button
                    className={styles.emptyBtn}
                    onClick={() => navigate(`/stories/${storyId}/assessment?run=1`)}
                  >
                    분석 시작하기
                  </button>
                )}
              </div>
            ))}
        </div>
        <button
          className={styles.cover}
          onClick={() => !loading && setOpened(true)}
          disabled={loading || opened}
          aria-label="리포트 펼치기"
        >
          <span className={styles.coverEdge} aria-hidden="true" />
          <span className={styles.coverBrand}>{BRAND}</span>
          <span className={styles.coverTitle}>분석 리포트</span>
          <span className={styles.coverHint}>{loading ? '준비하는 중…' : '눌러서 펼쳐보기'}</span>
        </button>
      </div>
    </div>
  );
}
