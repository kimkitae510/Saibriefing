import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { getAssessments, type AssessmentResponse } from '../api/assessment';
import { extractErrorMessage } from '../api/client';
import { GAUGE_MAX, bandLabel } from '../utils/assessmentScale';
import styles from './HistoryPage.module.css';

const CH_W = 320;
const CH_TOP = 28;
const CH_BOTTOM = 140;

function shortDate(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  return `${d.getMonth() + 1}/${d.getDate()}`;
}

// 하루에 여러 번 분석하면 날짜만으론 행이 안 갈린다 — 시간까지 붙인다.
function longDateTime(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  const h = d.getHours();
  const ampm = h < 12 ? '오전' : '오후';
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${d.getMonth() + 1}월 ${d.getDate()}일 ${ampm} ${h12}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function yFor(prob: number): number {
  return CH_BOTTOM - (Math.min(prob, GAUGE_MAX) / GAUGE_MAX) * (CH_BOTTOM - CH_TOP);
}

function xFor(i: number, n: number): number {
  if (n <= 1) return CH_W / 2;
  return 20 + (280 * i) / (n - 1);
}

export function HistoryPage() {
  const { storyId: storyIdParam } = useParams();
  const storyId = Number(storyIdParam);
  const navigate = useNavigate();

  const [items, setItems] = useState<AssessmentResponse[]>([]); // 최신순
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    (async () => {
      try {
        const all = await getAssessments(storyId);
        // 확률이 있는(POSSIBLE) 분석만 추이에 쓴다.
        if (aliveRef.current) setItems(all.filter((a) => a.probability != null));
      } catch (e) {
        if (aliveRef.current) setError(extractErrorMessage(e, '분석 기록을 불러오지 못했습니다.'));
      } finally {
        if (aliveRef.current) setLoading(false);
      }
    })();
    return () => {
      aliveRef.current = false;
    };
  }, [storyId]);

  const back = () => navigate(`/stories/${storyId}/assessment`);

  // 오래된→최신 순 (차트/증감 계산용)
  const asc = [...items].reverse();
  const oldest = asc[0]?.probability ?? 0;
  const latest = asc[asc.length - 1]?.probability ?? 0;
  const totalDelta = latest - oldest;
  const points = asc.map((a, i) => `${xFor(i, asc.length)},${yFor(a.probability ?? 0)}`).join(' ');

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.topbar}>
          <button className={styles.backButton} onClick={back} aria-label="뒤로">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 5l-7 7 7 7" stroke="#ebebee" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className={styles.topTitle}>분석 기록</div>
        </div>

        <div className={styles.body}>
          {loading ? (
            <div className={styles.state}>불러오는 중…</div>
          ) : error ? (
            <div className={styles.state}>{error}</div>
          ) : items.length === 0 ? (
            <div className={styles.state}>
              아직 분석 기록이 없습니다.
              <br />
              대화를 나눈 뒤 분석 리포트를 받아 보세요.
            </div>
          ) : (
            <>
              <div className={styles.summary}>
                <div className={styles.delta}>
                  {totalDelta > 0 ? '+' : ''}
                  {totalDelta}
                  <span className={styles.deltaUnit}>%</span>
                </div>
                <div className={styles.summaryCaption}>첫 분석과 비교한 변화 (분석 {items.length}회)</div>
              </div>

              {asc.length >= 2 && (
                <div className={styles.chartCard}>
                  <svg width="100%" viewBox="0 0 320 168" preserveAspectRatio="none" style={{ display: 'block' }}>
                    <line x1="20" y1="40" x2="300" y2="40" stroke="#2a2a2e" strokeWidth="1" />
                    <line x1="20" y1="90" x2="300" y2="90" stroke="#2a2a2e" strokeWidth="1" />
                    <line x1="20" y1="140" x2="300" y2="140" stroke="#2a2a2e" strokeWidth="1" />
                    <polyline points={points} fill="none" stroke="#6c6c74" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
                    {asc.map((a, i) => {
                      const last = i === asc.length - 1;
                      return (
                        <circle
                          key={i}
                          cx={xFor(i, asc.length)}
                          cy={yFor(a.probability ?? 0)}
                          r={last ? 5 : 3.5}
                          fill={last ? '#B89DD1' : '#161618'}
                          stroke={last ? 'none' : '#6c6c74'}
                          strokeWidth={last ? 0 : 2}
                        />
                      );
                    })}
                  </svg>
                  <div className={styles.chartAxis}>
                    {asc.map((a, i) => (
                      <span key={i}>{shortDate(a.createdAt)}</span>
                    ))}
                  </div>
                </div>
              )}

              {/* 행 오른쪽 증감의 기준(직전 분석 대비)이 안 보이면 숫자가 수수께끼가 된다 */}
              {items.length >= 2 && (
                <div className={styles.listCaption}>오른쪽 증감은 직전 분석과의 차이입니다</div>
              )}
              <div className={styles.list}>
                {items.map((a, i) => {
                  const prev = items[i + 1]; // 바로 이전(더 과거) 분석
                  const d =
                    prev && prev.probability != null && a.probability != null
                      ? a.probability - prev.probability
                      : null;
                  return (
                    <div className={styles.row} key={i}>
                      <div className={styles.rowMid}>
                        <div className={styles.rowDate}>{longDateTime(a.createdAt)}</div>
                        <div className={styles.rowBand}>{bandLabel(a.probability ?? 0)}</div>
                      </div>
                      {d != null && (
                        <span className={`${styles.rowDelta} ${d >= 0 ? styles.deltaUp : styles.deltaDown}`}>
                          {d > 0 ? '+' : ''}
                          {d}
                        </span>
                      )}
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </div>
      </div>
    </PhoneFrame>
  );
}
