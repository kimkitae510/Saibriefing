import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { getSharedAssessment, type SharedAssessmentResponse } from '../api/share';
import { GAUGE_MAX, bandLabel } from '../utils/assessmentScale';
import {
  FACTOR_LABEL,
  JUMP_CARD,
  NO_EVIDENCE,
  STAGE_LEVEL,
  TYPE_CHIP,
  TYPE_READING,
  psychRows,
} from '../utils/assessmentView';
import styles from './SharedResultPage.module.css';
import { BRAND } from '../brand';

const ARC_LEN = Math.PI * 120;

interface Card {
  name: string;
  level: string;
  evidence: string;
  rationale: string | null;
}

// 공유 링크로 열리는 읽기 전용 결과. 분석 화면이 읽는 것을 그대로 그린다 — 근거 문장까지
// 포함해서다(총평이 이미 사연을 서사로 요약해 나가는 판에 근거 한 줄만 가리면 기준이
// 서지 않는다). 무엇이 보이는지는 공유 버튼 옆 문구가 보내기 전에 말한다.
// 다른 점은 셋: 판단 안 된 중립 요인이 없고(남에겐 정보가 아니다), 비슷한 사례가 없고
// (서비스 자산이라 무인증 화면에 두지 않는다), 조작(재분석, 답 남기기)이 전부 빠졌다.
export function SharedResultPage() {
  const { token } = useParams();
  const navigate = useNavigate();
  const [result, setResult] = useState<SharedAssessmentResponse | null>(null);
  const [gone, setGone] = useState(false); // 죽은 링크(삭제된 방, 오타)

  useEffect(() => {
    let alive = true;
    getSharedAssessment(token ?? '')
      .then((r) => alive && setResult(r))
      .catch(() => alive && setGone(true));
    return () => {
      alive = false;
    };
  }, [token]);

  // 남의 사연이 실린 화면이라 검색에 걸리면 안 된다. 링크를 커뮤니티에 올리면 크롤러가
  // 따라 들어오고, 요즘 크롤러는 리액트가 그린 화면도 렌더해서 읽는다.
  // index.html은 서비스 전체가 쓰는 문서라 이 화면에 있는 동안만 심고 나갈 때 걷는다.
  useEffect(() => {
    const meta = document.createElement('meta');
    meta.name = 'robots';
    meta.content = 'noindex, nofollow, noarchive';
    document.head.appendChild(meta);
    return () => {
      document.head.removeChild(meta);
    };
  }, []);

  if (gone) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <div className={styles.state}>
            <div className={styles.stateTitle}>열 수 없는 링크입니다</div>
            <div className={styles.stateBody}>
              공유가 취소되었거나 이야기가 삭제되었습니다.
            </div>
          </div>
          <div className={styles.footer}>
            <button className={styles.btnPrimary} onClick={() => navigate('/')}>
              {BRAND} 시작하기
            </button>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  if (!result) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <div className={styles.state}>불러오는 중…</div>
        </div>
      </PhoneFrame>
    );
  }

  const prob = result.probability ?? 0;
  const fill = (Math.min(prob, GAUGE_MAX) / GAUGE_MAX) * ARC_LEN;

  // 분석 화면과 같은 2층 구성 — 유형(이별 사유)과 점프(이별 후 상황)가 대역을 정하는
  // 층이라 언제나 목록 맨 위다. 점프 카드는 사실 줄을 비운다(typeEvidence는 유형 카드의
  // 것이고 같은 문장을 두 장에 실으면 중복이다).
  const jumpCard = result.jumpRule ? JUMP_CARD[result.jumpRule] : undefined;
  const typeRaises = result.breakupType === '충동형' || result.breakupType === '상황형';
  const heads: Card[] = [];
  if (result.breakupType) {
    heads.push({
      name: '이별 사유',
      level: TYPE_CHIP[result.breakupType] ?? (typeRaises ? '유리' : '불리'),
      evidence: result.typeEvidence ?? '',
      rationale: TYPE_READING[result.breakupType] ?? null,
    });
  }
  if (jumpCard) {
    heads.push({
      name: '이별 후 상황',
      level: jumpCard.level,
      evidence: '',
      rationale: jumpCard.reading,
    });
  }
  const shown: Card[] = result.factors.map((f) => ({
    name: FACTOR_LABEL[f.name] ?? f.name,
    level:
      f.stage && (f.level === '불리' || f.level === '매우불리')
        ? STAGE_LEVEL[f.stage] ?? f.level
        : f.level,
    evidence: f.evidence,
    rationale: f.rationale,
  }));
  // 분석 화면과 같은 정렬 — 등급으로만 묶는다(정확한 증감은 백엔드만 알고, 상수를
  // 복제하면 언젠가 어긋난다). 안정 정렬이라 같은 등급 안에선 서버 순서가 유지된다.
  const raises = (c: Card) => c.level === '유리' || c.level === '매우유리';
  const strong = (l: string) => (l === '매우불리' || l === '매우유리' ? 0 : 1);
  const byWeight = (a: Card, b: Card) => strong(a.level) - strong(b.level);
  const unfavorable = [
    ...heads.filter((c) => !raises(c)),
    ...shown.filter((c) => c.level === '불리' || c.level === '매우불리').sort(byWeight),
  ];
  const favorable = [
    ...heads.filter(raises),
    ...shown.filter(raises).sort(byWeight),
  ];
  const psych = psychRows(result.relationshipPsychology);

  const cardList = (rows: Card[], weightClass: string) => (
    <div className={styles.dedList}>
      {rows.map((c) => (
        <div className={styles.dedItem} key={c.name}>
          <div className={styles.dedTop}>
            <div className={styles.dedSignal}>{c.name}</div>
            <span className={`${styles.weightLabel} ${weightClass}`}>{c.level}</span>
          </div>
          {c.evidence && c.evidence !== NO_EVIDENCE && (
            <div className={styles.dedEvidence}>{c.evidence}</div>
          )}
          {c.rationale && <div className={styles.dedRationale}>{c.rationale}</div>}
        </div>
      ))}
    </div>
  );

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.body}>
          <div className={styles.brand}>{BRAND}</div>
          <div className={styles.subTitle}>대화를 읽고 정리한 분석 결과</div>

          <div className={styles.gaugeWrap}>
            <svg width="280" height="150" viewBox="0 0 280 150">
              <path d="M20,138 A120,120 0 0 1 260,138" fill="none" stroke="#2a2a2e" strokeWidth="11" strokeLinecap="round" />
              <path
                d="M20,138 A120,120 0 0 1 260,138"
                fill="none"
                stroke="#B89DD1"
                strokeWidth="11"
                strokeLinecap="round"
                strokeDasharray={`${fill} ${ARC_LEN + 40}`}
              />
            </svg>
            <div className={styles.gaugeValue}>
              {/* 분석 화면과 같은 원칙 — 숫자 대신 등급 */}
              <div className={styles.gaugeBandBig}>{bandLabel(prob)}</div>
            </div>
          </div>
          <div className={styles.gaugeLabel}>재회 가능성</div>

          {result.reason && (
            <div className={styles.reasonCard}>
              <div className={styles.reasonLabel}>총평</div>
              {result.reason}
            </div>
          )}

          {/* 제안 확정(100%)이면 판정 셈을 숨긴다 — 수락만 남은 상태에 계산이 떠 있으면
              어색하다(분석 화면과 같은 원칙) */}
          {prob < 100 && (
            <>
              {unfavorable.length > 0 && (
                <>
                  <div className={styles.sectionHead}>
                    <span className={styles.sectionTitle}>가능성을 낮춘 신호</span>
                    <span className={`${styles.sectionCount} ${styles.weightMinus}`}>
                      {unfavorable.length}
                    </span>
                  </div>
                  {cardList(unfavorable, styles.weightMinus)}
                </>
              )}
              {favorable.length > 0 && (
                <>
                  <div className={styles.sectionHead}>
                    <span className={styles.sectionTitle}>가능성을 올린 신호</span>
                    <span className={`${styles.sectionCount} ${styles.weightPlus}`}>
                      {favorable.length}
                    </span>
                  </div>
                  {cardList(favorable, styles.weightPlus)}
                </>
              )}
            </>
          )}

          {psych.length > 0 && (
            <>
              <div className={styles.sectionHead}>
                <span className={styles.sectionTitle}>우리 관계는 왜 힘들었을까</span>
              </div>
              <div className={styles.dedList}>
                {psych.map((row) => (
                  <div className={styles.dedItem} key={row.name}>
                    <div className={styles.dedTop}>
                      <div className={styles.dedSignal}>{row.name}</div>
                      <span className={`${styles.weightLabel} ${styles.weightNeutral}`}>
                        {row.value}
                      </span>
                    </div>
                    {row.description && (
                      <div className={styles.dedRationale}>{row.description}</div>
                    )}
                  </div>
                ))}
              </div>
            </>
          )}

          {prob < 100 && result.relapseRisk && (
            <>
              <div className={styles.sectionHead}>
                <span className={styles.sectionTitle}>다시 만나면 같은 문제가 반복될까</span>
              </div>
              <div className={styles.dedList}>
                <div className={styles.dedItem}>
                  <div className={styles.dedTop}>
                    <div className={styles.dedSignal}>재발 위험</div>
                    <span
                      className={`${styles.weightLabel} ${
                        result.relapseRisk === '높음'
                          ? styles.weightMinus
                          : result.relapseRisk === '낮음'
                            ? styles.weightPlus
                            : styles.weightNeutral
                      }`}
                    >
                      {result.relapseRisk}
                    </span>
                  </div>
                  {result.relapseReason && (
                    <div className={styles.dedRationale}>{result.relapseReason}</div>
                  )}
                </div>
              </div>
            </>
          )}

          <div className={styles.note}>
            당사자가 나눈 대화만 근거로 계산한 결과입니다. 당사자가 링크를 끄면 이 페이지는 닫힙니다.
          </div>
        </div>

        <div className={styles.footer}>
          <button className={styles.btnPrimary} onClick={() => navigate('/')}>
            나도 분석 리포트 받기
          </button>
          <div className={styles.foot}>가입 없이 바로 이야기를 시작할 수 있어요</div>
        </div>
      </div>
    </PhoneFrame>
  );
}
