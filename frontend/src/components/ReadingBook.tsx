import { useState } from 'react';
import type { ReadingView, StoryReport } from '../api/assessment';
import { bandLabel } from '../utils/assessmentScale';
import styles from './ReadingBook.module.css';

// 정밀 판독 뷰어 — 세로 스크롤 문서 하나, 세 부다.
// 01 결론: 확률과 판정 전부(낮춘 것/높인 것 포함). 결론을 찾으러 스크롤하게 만들지 않는다.
// 02 상황 이해: 행동 계획 전에 알아야 하는 것들 — 심층 장들이 여기 들어간다.
//    이 판이 왜 이렇게 됐는지를 모른 채 움직이면 같은 자리에서 다시 어긋나기 때문이다.
// 03 행동 계획.

// 근거가 아직 부분인 항목은 그 사실을 밝힌다: 판단한 것과 못 물어본 것은 다르다.
const EVIDENCE_NOTE: Record<string, string> = {
  ABSENCE_CONFIRMED: '없음 확인',
  PARTIAL: '근거 부족',
};

// 한 줄 게이지 — 낮음에서 높음까지의 눈금 위에 지금 위치를 점 하나로 찍는다.
function Gauge({ probability }: { probability: number }) {
  const left = Math.max(2, Math.min(98, probability));
  return (
    <div className={styles.gauge}>
      <div className={styles.gaugeHead}>
        재회 가능성 <span className={styles.gaugeBand}>{bandLabel(probability)}</span>
      </div>
      <div className={styles.gaugeTrack}>
        <div className={styles.gaugeDot} style={{ left: `${left}%` }} />
      </div>
      <div className={styles.gaugeScale}>
        <span>낮음</span>
        <span>보통</span>
        <span>높음</span>
      </div>
    </div>
  );
}

// 시간이 지나면 올라갈 수 있는 확률이라는 마크 — 게이지 바로 밑에 붙인다.
// 게이지는 "지금"의 스냅샷이고 이 마크는 그 숫자의 시간 방향이다. 떨어뜨려 놓으면
// 숫자를 확정으로 읽는다. 근거가 있는 사연에만 내려오므로 활성 여부는 다시 안 따진다.
// 강도(strength)는 화면에 쓰지 않는다 — 강약 표시가 붙으면 판독이 예언처럼 읽힌다.
function DelayedRegretMark({ mark }: { mark: NonNullable<StoryReport['delayedRegret']> }) {
  const [open, setOpen] = useState(false);
  return (
    <div className={styles.regret}>
      <button className={styles.regretHead} onClick={() => setOpen(!open)} aria-expanded={open}>
        <span className={styles.regretLabel}>시간이 지나면 올라갈 수 있는 등급입니다</span>
        <span className={styles.regretLead}>{mark.headline ?? mark.whyLater}</span>
      </button>
      {open && (
        <div className={styles.regretBody}>
          <div className={styles.regretLine}>
            <span className={styles.regretWhen}>지금은</span>
            {mark.whyNotNow}
          </div>
          <div className={styles.regretLine}>
            <span className={styles.regretWhen}>시간이 지나면</span>
            {mark.whyLater}
          </div>
          <div className={styles.regretLine}>
            <span className={styles.regretWhen}>그렇게 보는 이유</span>
            {mark.basis}
          </div>
          {mark.limit && <div className={styles.regretLimit}>{mark.limit}</div>}
        </div>
      )}
    </div>
  );
}

// 재진단이 쌓였을 때만 그리는 추세 — 실제 변화가 있을 때 값이 있다.
function Trend({ history }: { history: number[] }) {
  return (
    <div className={styles.trend}>
      <div className={styles.trendLabel}>지난 분석에서 지금까지</div>
      <div className={styles.trendRow}>
        {history.map((p, i) => (
          <span key={i} className={styles.trendItem}>
            {i > 0 && <span className={styles.trendArrow}>→</span>}
            <span className={i === history.length - 1 ? styles.trendNow : undefined}>{p}%</span>
          </span>
        ))}
      </div>
    </div>
  );
}

// 진단 한 줄. 접혀 있고, 누르면 왜 그렇게 봤는지가 펼쳐진다.
// 방향은 그룹이 이미 말하므로 칩은 "크게"(매우유리/매우불리)일 때만 붙인다 —
// 모든 줄에 낮춤/높임이 반복되면 그룹 라벨과 이중이 된다.
function DiagnosisRow({
  item,
  open,
  onToggle,
}: {
  item: StoryReport['diagnosis'][number];
  open: boolean;
  onToggle: () => void;
}) {
  const up = item.level.includes('유리');
  const strong = item.level.startsWith('매우');
  const note = EVIDENCE_NOTE[item.evidenceState];
  return (
    <div className={styles.diagItem}>
      <button className={styles.diagTop} onClick={onToggle} aria-expanded={open}>
        <span className={styles.diagLabel}>{item.label}</span>
        {note && <span className={styles.diagNote}>{note}</span>}
        {strong && (
          <span className={`${styles.diagImpact} ${up ? styles.impactUp : styles.impactDown}`}>
            {up ? '크게 높임' : '크게 낮춤'}
          </span>
        )}
        {/* 접혀 있다는 걸 화면이 말해준다 — 표시가 없으면 아무도 누르지 않는다 */}
        {item.reading && (
          <span className={`${styles.diagCaret} ${open ? styles.diagCaretOpen : ''}`} aria-hidden="true">
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none">
              <path d="M6 9l6 6 6-6" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </span>
        )}
      </button>
      <div className={styles.diagVerdict}>{item.headline}</div>
      {open && item.reading && <div className={styles.diagReading}>{item.reading}</div>}
    </div>
  );
}

// 방향 그룹 — 라벨 색이 방향을 말한다(카드마다 방향 칩을 반복하지 않기 위함).
function DiagnosisGroup({
  title,
  tone,
  items,
  openKey,
  onToggle,
}: {
  title: string;
  tone: 'up' | 'down' | 'hold';
  items: StoryReport['diagnosis'];
  openKey: string | null;
  onToggle: (key: string) => void;
}) {
  if (items.length === 0) return null;
  const toneClass =
    tone === 'up' ? styles.groupUp : tone === 'down' ? styles.groupDown : styles.groupHold;
  return (
    <div className={styles.diagGroup}>
      <div className={`${styles.diagGroupLabel} ${toneClass}`}>{title}</div>
      <div className={styles.diagList}>
        {items.map((item) => (
          <DiagnosisRow
            key={item.key}
            item={item}
            open={openKey === item.key}
            onToggle={() => onToggle(item.key)}
          />
        ))}
      </div>
    </div>
  );
}

// 재회 판 라벨 — 숫자는 코드가 level에서 계산한 판독 점수라 통계 확률처럼 말하지 않는다.
// 등급 자체가 현재 판의 요약 역할을 한다 — MID를 "반반"이 아니라 "열려 있음"으로 두는 이유.
// 판별 이유 그룹 제목 — 판정(게이지) 아래의 한눈 리캡이라 등급 어휘를 그대로 쓴다.
// 긴 논증은 1장 서사가 맡고 카드는 요지만. 주 방향이 먼저, 경계 방향이 뒤에 선다.
const REASON_GROUPS: Record<
  string,
  { mainDir: 'UP' | 'DOWN'; main: string; counter: string }
> = {
  VERY_LOW: {
    mainDir: 'DOWN',
    main: '재회 가능성을 매우 낮게 본 이유',
    counter: '그래도 덧붙여 둘 것',
  },
  LOW: {
    mainDir: 'DOWN',
    main: '재회 가능성을 낮게 본 이유',
    counter: '하지만 매우 낮다고 볼 수는 없는 이유',
  },
  MID: {
    mainDir: 'UP',
    main: '재회 가능성이 열려 있다고 본 이유',
    counter: '하지만 재회 가능성이 높다고 볼 수는 없는 이유',
  },
  HIGH: {
    mainDir: 'UP',
    main: '재회 가능성을 높게 본 이유',
    counter: '하지만 매우 높다고 볼 수는 없는 이유',
  },
  VERY_HIGH: {
    mainDir: 'UP',
    main: '재회 가능성을 매우 높게 본 이유',
    counter: '그래도 덧붙여 둘 것',
  },
};

// 눈금 네 자리 — 등급은 연속값이 아니라 네 칸이다. 중간(MID)은 판단을 피하는 자리라 뺐다(2026-09-15).
const OUTLOOK_POSITIONS: { level: string; pos: number; short: string }[] = [
  { level: 'VERY_LOW', pos: 0, short: '매우 낮음' },
  { level: 'LOW', pos: 33, short: '낮음' },
  { level: 'HIGH', pos: 67, short: '높음' },
  { level: 'VERY_HIGH', pos: 100, short: '매우 높음' },
];
// 옛 저장분(MID)만 다섯 칸으로 그린다 — 새 판독은 네 칸 중에서만 고른다.
const OUTLOOK_POSITIONS_LEGACY: { level: string; pos: number; short: string }[] = [
  { level: 'VERY_LOW', pos: 0, short: '매우 낮음' },
  { level: 'LOW', pos: 25, short: '낮음' },
  { level: 'MID', pos: 50, short: '열려 있음' },
  { level: 'HIGH', pos: 75, short: '높음' },
  { level: 'VERY_HIGH', pos: 100, short: '매우 높음' },
];

// 판정 카드 — body가 분석 문단 통째라 길다. 접힌 상태에서 첫 두 줄만 보여
// 뒷내용을 궁금하게 만들고, 카드를 누르면 원문 전체가 열린다.
// 핵심(pivot) 카드만 펼친 채 시작한다 — 첫 화면에서 깊이를 증명하는 몫은 등급을
// 만든 판단이 맡고, 나머지는 제목 스택이 훅이 된다. body 없는 카드는 제목만으로 선다.
function VerdictCardBox({ block }: {
  block: { subtitle: string; body?: string | null; pivot?: boolean | null };
}) {
  const [open, setOpen] = useState(!!block.pivot);
  // 제목 끝 마침표는 화면에서 뗀다 — 목록으로 늘어서면 마침표가 스캔을 무겁게 한다
  const title = block.subtitle.replace(/\.$/, '');
  if (!block.body) {
    return (
      <div className={styles.verdictCard}>
        <div className={styles.verdictCardTitle}>{title}</div>
      </div>
    );
  }
  return (
    <div
      className={`${styles.verdictCard} ${styles.verdictCardClickable}`}
      onClick={() => setOpen((v) => !v)}
    >
      <div className={styles.verdictCardTitle}>{title}</div>
      <div
        className={open
          ? styles.verdictCardBody
          : `${styles.verdictCardBody} ${styles.verdictCardPreview}`}
      >
        {block.body}
      </div>
      <div className={styles.verdictCardMore}>{open ? '접기' : '더 읽기'}</div>
    </div>
  );
}

export function ReadingBook({
  reading,
  probability,
  history,
}: {
  reading: ReadingView;
  probability: number | null;
  // 재진단 확률 이력(과거→현재). 2개 이상일 때만 추세로 그린다.
  history: number[];
  // 칩을 누르면 채팅으로 — 질문이 입력창에 미리 채워진다.
}) {
  const [openKey, setOpenKey] = useState<string | null>(null);
  const report = reading.report;
  const delta = reading.delta;
  const plan = report.actionPlan; // baseline은 03을 안 만들어 null
  const toggle = (key: string) => setOpenKey(openKey === key ? null : key);
  const decision = report.decision ?? null;

  // 4막 — 결정이 있는 리포트(현행). 진단 카드와 확률 게이지는 화면에서 내렸다:
  // 유형, 요인, 확률은 그림자 데이터로만 남고, 판은 분석이 만든 결정이 말한다.
  if (decision) {
    const groups = REASON_GROUPS[decision.outlookLevel] ?? REASON_GROUPS.LOW;
    const positions = decision.outlookLevel === 'MID' ? OUTLOOK_POSITIONS_LEGACY : OUTLOOK_POSITIONS;
    const mainReasons = decision.reasons.filter((r) => r.direction === groups.mainDir);
    const counterReasons = decision.reasons.filter((r) => r.direction !== groups.mainDir);
    // 판정 카드도 같은 그룹 문법으로 — 올린 판정과 내린 판정을 등급에 맞는 제목 아래 묶는다.
    // direction 없는 카드(도입 직전 저장분)는 묶지 않고 순서대로 그린다.
    const verdicts = decision.verdictBlocks ?? [];
    // 핵심 판단(pivot)은 그룹 맨 위로 — 표시는 장식이 아니라 자리로 말한다
    const pivotFirst = <T extends { pivot?: boolean | null }>(list: T[]) =>
      [...list].sort((a, b) => Number(!!b.pivot) - Number(!!a.pivot));
    const mainVerdicts = pivotFirst(verdicts.filter((v) => v.direction === groups.mainDir));
    const counterVerdicts = pivotFirst(
      verdicts.filter((v) => v.direction && v.direction !== groups.mainDir));
    const plainVerdicts = verdicts.filter((v) => !v.direction);
    // 낮은 판(내림이 주 방향)은 내림을 한꺼번에 쏟지 않는다 — 결정타(핵심) 하나만 먼저,
    // 그 다음 열어 두는 이유와 미지수, 나머지 내림은 뒤로. 등급과 요지가 먼저 자리를
    // 잡고 있어 희망 카드가 본문으로 읽히지는 않는다. 높은 판은 원래 순서.
    const lowGrade = groups.mainDir === 'DOWN';
    const leadCount = lowGrade
      ? Math.min(2, Math.max(1, mainVerdicts.filter((v) => v.pivot).length))
      : mainVerdicts.length;
    const mainLead = mainVerdicts.slice(0, leadCount);
    const mainRest = mainVerdicts.slice(leadCount);
    // 장은 재료가 있을 때만 선다 — 새 파이프라인은 상대 마음 서사가 판정 카드로
    // 흡수되면 mind를 비우고, 표시 없는 문단이 없으면 analysis도 빈다.
    // 번호는 실제로 선 장 순서대로 매긴다.
    // 새 세대 판별 — outlookAnalysis(등급 요지)는 리포트 판형 전환 후 판만 채운다.
    // 옛 저장분은 이 분기로 기존 카드 화면을 유지한다.
    const isNewGen = !!decision.outlookAnalysis;
    const proBlocks = decision.prologueBlocks ?? [];
    // 리드(공감 서사)는 생성 지시가 분석 첫 문단으로 고정하고 편집은 순서를 보존하므로
    // 첫 블록을 리드로 올린다. 오식별 판이 실측되면 서버 파싱으로 승격.
    const leadBlock = isNewGen && proBlocks.length > 0 ? proBlocks[0] : null;
    const storyBlocks = leadBlock ? proBlocks.slice(1) : proBlocks;
    const hasStory = storyBlocks.length > 0 || !!decision.prologueEmpathy
      || !!decision.prologueReality || !!decision.prologue || !!decision.hook;
    // 소견서 판형 — 분석 문단 전부를 원문 순서대로 두고 판을 가른 문단에 표시만 붙인다.
    // 카드를 오려내 목록으로 세우면 대목이 끊기고, 남은 문단이 딴 장으로 밀려나는 게
    // 실측이라 글 하나로 그린다. 미정(판을 바꾸는 조건)만 글에서 빼서 맨 아래 따로 둔다.
    const essay = decision.readingBlocks?.length ? decision.readingBlocks : null;
    const essayLead = essay && !essay[0].subtitle && !essay[0].direction ? essay[0] : null;
    const essayBody = essay
      ? (essayLead ? essay.slice(1) : essay).filter((b) => b.direction !== 'NONE')
      : [];
    const essayHinge = essay ? essay.filter((b) => b.direction === 'NONE') : [];
    // 라벨은 방향이 먼저, 등급을 만든 판단이면 그 뒤에 덧붙인다 — 핵심 표시가 방향을 가리면
    // 훑는 사람이 그 문단이 올린 건지 내린 건지 모른다.
    // 정리표 키워드가 이 문단에서 온 것이면 그 키워드가 라벨이다 — 위 카드와 같은 말이라
    // 훑어 내려가면서 둘이 이어진다. 키워드 없는 표시 문단만 일반 문구.
    const markLabel = (b: { direction?: string | null; pivot?: boolean | null; keyword?: string | null }) => {
      const dir = b.direction === 'UP' ? '올리는 것' : b.direction === 'DOWN' ? '내리는 것' : '';
      const head = b.keyword
        ? (dir ? `${dir} — ${b.keyword}` : b.keyword)
        : (b.direction === 'UP' ? '가능성을 올리는 판단' : '가능성을 내리는 판단');
      return head + (b.pivot ? ', 등급을 만든 판단' : '');
    };
    // 문단 id는 소견서 전체 순번 — 본문과 아래 미정 구간 모두 같은 순번을 쓴다.
    const blockId = (b: { body: string }) => `essay-blk-${essay ? essay.indexOf(b as never) : -1}`;
    // 책갈피 줄 — 올린 이유와 내린 이유를 키워드로만 한 줄씩. 결론 문장이 아니라 이름이라
    // 글을 읽기 전에 답을 다 보여주지 않는다. 등급을 만든 문단의 책갈피에는 (핵심)이 붙고,
    // 누르면 그 책갈피가 꽂힌 문단으로 내려간다. 꽂힌 문단이 없는 책갈피는 밑줄 없이 선다.
    // 카드를 누르면 그 책갈피가 꽂힌 문단으로 내려가고 그 문단이 잠깐 밝아진다 — 카드와
    // 문단이 같은 것을 가리킨다는 걸 위치와 표시로 잇는다(문단 머리 라벨에 같은 이름이 서 있다).
    const jumpTo = (keyword: string) => {
      const target = essay?.find((b) => b.keyword === keyword);
      if (!target) return;
      const el = document.getElementById(blockId(target));
      if (!el) return;
      el.scrollIntoView({ behavior: 'smooth', block: 'center' });
      el.classList.add(styles.essayFlash);
      window.setTimeout(() => el.classList.remove(styles.essayFlash), 1800);
    };
    // 책갈피 카드 — 이름과 한 줄. 카드만 읽어도 무엇이 왜인지는 알고, 어떻게 그런지는 문단이
    // 맡는다(문단을 카드에 넣으면 분석이 두 번 읽힌다). 등급을 만든 문단의 카드가 맨 위.
    const bookmarkCards = (title: string, items: { keyword: string; note?: string | null }[]) => {
      if (!items?.length) return null;
      const withBlock = items.map((it) => ({ it, block: essay?.find((b) => b.keyword === it.keyword) }));
      const ordered = [...withBlock].sort((a, b) => Number(!!b.block?.pivot) - Number(!!a.block?.pivot));
      return (
        <div className={styles.diagGroup}>
          <div className={styles.diagGroupLabel}>{title}</div>
          {ordered.map(({ it, block }, i) => (
            <div
              key={i}
              className={block
                ? `${styles.verdictCard} ${styles.verdictCardClickable}`
                : styles.verdictCard}
              onClick={() => jumpTo(it.keyword)}
            >
              <div className={styles.verdictCardTitle}>
                {it.keyword}{block?.pivot ? ' (핵심)' : ''}
              </div>
              {it.note && <div className={styles.bookmarkNote}>{it.note}</div>}
            </div>
          ))}
        </div>
      );
    };
    let sectionNo = 1;
    const nextNo = () => `0${(sectionNo += 1)}`;
    return (
      <div className={styles.scrollWrap}>
        {/* 리드 — 판정보다 먼저 마음을 사는 공감과 재정의 서사. 제목 없이 문단만.
            소견서 판형에서는 등급 아래 글의 첫 문단으로 들어간다(위에 두면 글이 게이지에 끊긴다) */}
        {!essay && leadBlock && <div className={styles.leadProse}>{leadBlock.body}</div>}
        {/* 판정이 먼저 선다 — 온 목적(가능성)에 즉시 답해야 뒤의 긴 분석이 "왜?"의
            답으로 읽힌다(분석부터 세우면 첫 화면이 시험지가 된다). 게이지 + 판정 한 줄 +
            이유 카드. 낮은 판의 완충은 게이지 바로 밑 verdictLine이 맡는다 */}
        <section className={styles.scrollSection}>
          <div className={styles.scrollHead}>
            <span className={styles.scrollNum}>01</span>
            <span className={styles.scrollTitle}>먼저, 재회 가능성부터 말씀드리겠습니다</span>
          </div>
          {/* 옛 에디토리얼 문법 — 상자 없이 게이지(줄+점), 총평, 그룹 리스트가 층을 만든다.
              숫자는 안 보여준다: 실측 없는 %는 정밀도를 사칭한다. 등급 라벨과 점 위치만.
              눈금과 라벨은 다섯 — 이유 그룹 제목이 쓰는 5단계 어휘와 눈금이 같아야 한다.
              "재회 가능성" 머리글은 뺐다: 섹션 제목이 이미 그 질문이고, 등급 어절이 답이다. */}
          {/* 등급 라벨 텍스트는 뺐다 — 눈금 하이라이트가 같은 단어를 이미 말한다(중복).
              섹션 제목이 질문, 눈금의 현재 위치가 답이 되는 구도 */}
          {/* 다섯 칸 세그먼트 바 — 선 위의 점보다 등급이 "칸"이라는 사실이 그대로 보인다.
              채움은 현재 칸 하나만(화면의 유일한 보라), 라벨은 칸 아래 정렬 */}
          <div className={styles.gauge}>
            <div className={styles.gaugeSegs}>
              {positions.map(({ level }) => (
                <span
                  key={level}
                  className={
                    level === decision.outlookLevel
                      ? `${styles.gaugeSeg} ${styles.gaugeSegOn}`
                      : styles.gaugeSeg
                  }
                />
              ))}
            </div>
            <div className={styles.gaugeLabels}>
              {positions.map(({ level, short }) => (
                <span
                  key={level}
                  className={level === decision.outlookLevel ? styles.gaugeLabelOn : undefined}
                >
                  {short}
                </span>
              ))}
            </div>
          </div>
          {/* 등급 밑 한 줄 요지 — 막는 것과 열어 두는 것을 압축한 분석의 맺음 문장을
              배분해 싣는다(리포트 개편). luna 창작 총평(verdictLine)은 여전히 안 싣는다. */}
          {/* 정리표 — 이 이별의 이름 한 줄, 올리는 것과 내리는 것 키워드, 지금 이 등급인 이유.
              분류 호출이 분석에서 뽑은 것이라 아래 글과 어긋나지 않는다. 배지와 색 없이 글줄로 */}
          {/* 정리표는 루브릭 시절 이유 카드의 문법으로 — 등급 어휘 그룹 제목("재회 가능성을 높게
              본 이유" / "하지만 매우 높다고 볼 수는 없는 이유") 아래 책갈피가 카드로 선다.
              카드를 누르면 그 책갈피가 꽂힌 원문 문단이 그 자리에서 펼쳐진다. 이름 한 줄이 위,
              등급 이유 한 줄이 게이지 바로 밑 */}
          {decision.summary?.why && (
            <p className={styles.gaugeSummary}>{decision.summary.why}</p>
          )}
          {!decision.summary && decision.outlookAnalysis && (
            <p className={styles.gaugeSummary}>{decision.outlookAnalysis}</p>
          )}
          {decision.summary?.name && (
            <div className={styles.summaryName}>{decision.summary.name}</div>
          )}
          {/* 판독 항목 — 모델이 이 사연에서 고른 판단 대상들. "이름: 결과 — 설명" 한 줄을 세 단으로 가른다.
              긴 본문을 안 읽어도 전문가가 이 관계를 어떻게 보는지 여기서 읽힌다(81판) */}
          {!!decision.summary?.types?.length && (
            <div className={styles.diagGroup}>
              <div className={styles.diagGroupLabel}>재회 가능성 판독</div>
              {decision.summary.types.map((t, i) => {
                const colon = t.indexOf(': ');
                const name = colon > 0 ? t.slice(0, colon) : '';
                const rest = colon > 0 ? t.slice(colon + 2) : t;
                const dash = rest.indexOf(' — ');
                const result = dash > 0 ? rest.slice(0, dash) : rest;
                const note = dash > 0 ? rest.slice(dash + 3) : '';
                return (
                  <div className={styles.typeLine} key={i}>
                    {name && <div className={styles.typeName}>{name}</div>}
                    <div className={styles.typeResult}>{result}</div>
                    {note && <div className={styles.typeNote}>{note}</div>}
                  </div>
                );
              })}
            </div>
          )}
          {decision.summary && bookmarkCards(
            groups.main, groups.mainDir === 'UP' ? decision.summary.up : decision.summary.down)}
          {decision.summary && bookmarkCards(
            groups.counter, groups.mainDir === 'UP' ? decision.summary.down : decision.summary.up)}
          {report.delayedRegret && <DelayedRegretMark mark={report.delayedRegret} />}
          {/* 소견서 본문 — 소제목은 sol이 쓴 대목 결론, 표시 문단은 라벨 한 줄과 왼쪽 선.
              라벨은 말로만(배지, 화살표, 색 없음) — 훑는 사람은 라벨 줄만 따라 내려간다 */}
          {essayLead && <div className={styles.essayLead}>{essayLead.body}</div>}
          {essay && essayBody.map((b, i) => (
            <div className={styles.essayBlock} key={i} id={blockId(b)}>
              {/* 소제목은 대목의 것이라 표시 문단의 선 밖에 선다 */}
              {b.subtitle && (
                <div className={b.pivot
                  ? `${styles.chapterProseTitle} ${styles.essayTitlePivot}`
                  : styles.chapterProseTitle}
                >
                  {b.subtitle}
                </div>
              )}
              {/* 표시는 책갈피가 꽂힌 문단에만 — 라벨만 있고 책갈피가 없는 문단에 "가능성을 올리는
                  판단" 같은 일반 문구를 세우면 위 카드와 잇는 말이 없어서 뜬금없다 */}
              {b.keyword ? (
                <div className={styles.essayMarked}>
                  <div className={styles.essayLabel}>{markLabel(b)}</div>
                  <div className={styles.chapterProse}>{b.body}</div>
                </div>
              ) : (
                <div className={styles.chapterProse}>{b.body}</div>
              )}
            </div>
          ))}
          {/* 판정 카드 — 소제목이 판정 결론 문장(충동이었지 마음이 떠난 게 아니다 류),
              본문이 그 판정이 가능성을 왜 움직이는지. 올린 판정과 내린 판정을 등급 어휘
              그룹 제목 아래 묶는다. 라벨 카드(reasons)는 카드 세대 저장분 전용 */}
          {/* 헤더 바는 전부 브랜드 보라 — 방향은 문구와 개수가 말한다 */}
          {!essay && mainLead.length > 0 && (
            <div className={styles.diagGroup}>
              <div className={styles.diagGroupLabel}>
                {groups.main}
                <span className={styles.groupCount}>{mainVerdicts.length}</span>
              </div>
              {mainLead.map((block, i) => (
                <VerdictCardBox block={block} key={i} />
              ))}
            </div>
          )}
          {!essay && counterVerdicts.length > 0 && (
            <div className={styles.diagGroup}>
              <div className={styles.diagGroupLabel}>
                {groups.counter}
                <span className={styles.groupCount}>{counterVerdicts.length}</span>
              </div>
              {counterVerdicts.map((block, i) => (
                <VerdictCardBox block={block} key={i} />
              ))}
            </div>
          )}
          {/* 방향 없는 카드 — 값이 확인되면 판이 움직이는 미지수. 방향 그룹이 있는
              신세대 저장분에만 헤더를 단다(구세대 저장분은 전부 방향 없음이라 오라벨). */}
          {!essay && plainVerdicts.length > 0 &&
            (mainVerdicts.length > 0 || counterVerdicts.length > 0 ? (
              <div className={styles.diagGroup}>
                <div className={styles.diagGroupLabel}>
                  가능성을 올릴 수도, 낮출 수도 있는 요인
                  <span className={styles.groupCount}>{plainVerdicts.length}</span>
                </div>
                {plainVerdicts.map((block, i) => (
                  <VerdictCardBox block={block} key={i} />
                ))}
              </div>
            ) : (
              plainVerdicts.map((block, i) => (
                <VerdictCardBox block={block} key={i} />
              ))
            ))}
          {!essay && mainRest.length > 0 && (
            <div className={styles.diagGroup}>
              <div className={styles.diagGroupLabel}>
                {groups.main} — 이어서
                <span className={styles.groupCount}>{mainRest.length}</span>
              </div>
              {mainRest.map((block, i) => (
                <VerdictCardBox block={block} key={i} />
              ))}
            </div>
          )}
          {!(decision.verdictBlocks ?? []).length && mainReasons.length > 0 && (
            <div className={styles.diagGroup}>
              <div className={`${styles.diagGroupLabel} ${styles.groupUp}`}>{groups.main}</div>
              <div className={styles.diagList}>
                {mainReasons.map((r, i) => (
                  <div className={styles.diagItem} key={i}>
                    <div className={styles.diagLabel}>{r.label}</div>
                    {r.reading && <div className={styles.diagVerdict}>{r.reading}</div>}
                  </div>
                ))}
              </div>
            </div>
          )}
          {!(decision.verdictBlocks ?? []).length && counterReasons.length > 0 && (
            <div className={styles.diagGroup}>
              <div className={`${styles.diagGroupLabel} ${styles.groupUp}`}>{groups.counter}</div>
              <div className={styles.diagList}>
                {counterReasons.map((r, i) => (
                  <div className={styles.diagItem} key={i}>
                    <div className={styles.diagLabel}>{r.label}</div>
                    {r.reading && <div className={styles.diagVerdict}>{r.reading}</div>}
                  </div>
                ))}
              </div>
            </div>
          )}
        </section>

        {/* 판을 바꾸는 조건 — 확인되면 판이 움직이는 미정 문단들. 글에서 빼서 다 읽은 뒤
            마지막에 선다. 잠금은 아직 안 건다(내용을 눈으로 확인하는 단계) */}
        {essay && essayHinge.length > 0 && (
        <section className={styles.scrollSection}>
          <div className={styles.scrollHead}>
            <span className={styles.scrollNum}>{nextNo()}</span>
            <span className={styles.scrollTitle}>이 판을 바꿀 수 있는 것</span>
          </div>
          {essayHinge.map((b, i) => (
            <div className={styles.essayBlock} key={i} id={blockId(b)}>
              {b.subtitle && <div className={styles.chapterProseTitle}>{b.subtitle}</div>}
              <div className={styles.essayMarked}>
                {(b.subtitle || i === 0) && (
                  <div className={styles.essayLabel}>확인되면 판이 달라질 수 있는 것</div>
                )}
                <div className={styles.chapterProse}>{b.body}</div>
              </div>
            </div>
          ))}
        </section>
        )}

        {/* 상대 마음 장은 폐지 — 마음 읽기는 판정 카드가 흡수하고, 새 파이프라인은
            mind를 비운다. 옛 저장분의 mind도 그리지 않는다(장 구성 통일). */}
        {/* 판을 가르지 않은 문단들의 자리 — 배경 해부, 사연자를 향한 통찰.
            소견서 판형에서는 전부 1장에 있으니 서지 않는다 */}
        {!essay && hasStory && (
        <section className={styles.scrollSection}>
          {/* 장 제목 위계는 전 장 동일 — 이 장만 작아 보이던 prologueHead 축소를 걷어냈다 */}
          <div className={styles.scrollHead}>
            <span className={styles.scrollNum}>{nextNo()}</span>
            <span className={styles.scrollTitle}>지금 놓치고 있을 수도 있는 부분</span>
          </div>
          {decision.hook && (
            <>
              <div className={styles.hookDash} />
              <div className={styles.prologueHook}>{decision.hook}</div>
            </>
          )}
          {/* 새 세대는 흐르는 산문 — 해부 서사는 카드로 끊으면 견인력이 죽는다.
              1장(확인 도구)은 카드, 2장(읽는 글)은 산문으로 역할을 가른다 */}
          {isNewGen
            ? storyBlocks.map((block, i) => (
              <div className={styles.chapterProse} key={i}>
                {block.subtitle && (
                  <div className={styles.chapterProseTitle}>{block.subtitle}</div>
                )}
                {block.body}
              </div>
            ))
            : storyBlocks.map((block, i) => (
              <VerdictCardBox block={block} key={i} />
            ))}
          {/* 블록 이전 세대 저장분 — 공감/현실 2필드 또는 통짜 서사 */}
          {!decision.prologueBlocks?.length && decision.prologueEmpathy && (
            <div className={styles.prologueBody}>{decision.prologueEmpathy}</div>
          )}
          {!decision.prologueBlocks?.length && decision.prologueReality && (
            <div className={styles.prologueReality}>{decision.prologueReality}</div>
          )}
          {!decision.prologueBlocks?.length && !decision.prologueEmpathy
            && !decision.prologueReality && decision.prologue && (
            <div className={styles.prologueBody}>{decision.prologue}</div>
          )}
        </section>
        )}

        {/* 당신이 궁금했던 것 */}
        {decision.answers.length > 0 && (
          <section className={styles.scrollSection}>
            <div className={styles.scrollHead}>
              <span className={styles.scrollNum}>{nextNo()}</span>
              <span className={styles.scrollTitle}>내가 가장 궁금했던 것</span>
            </div>
            {decision.answers.map((qa, i) => (
              <div className={styles.chapterBlock} key={i}>
                <div className={styles.chapterAsk}>{qa.question}</div>
                <div className={styles.chapterBody}>{qa.answer}</div>
              </div>
            ))}
          </section>
        )}

        {/* 심층 장은 폐지(2026-08-26) — 1장 분석이 원석 전체라 별도 장이 군더더기다.
            2단 파이프라인 이전 저장분만 analysisChapters를 그린다 */}
        {(decision.actionBlocks ?? []).length === 0 && report.analysisChapters.length > 0 && (
          <section className={styles.scrollSection}>
            <div className={styles.scrollHead}>
              <span className={styles.scrollNum}>{nextNo()}</span>
              <span className={styles.scrollTitle}>이 관계에서 진짜 중요했던 것</span>
            </div>
            {report.analysisChapters.map((item, i) => (
              <div className={styles.chapterBlock} key={i}>
                <div className={styles.chapterTitle}>{item.verdict ?? item.title}</div>
                <div className={styles.chapterBody}>{item.reading}</div>
              </div>
            ))}
          </section>
        )}

        {/* 그래서 지금 어떻게 할까 — 2단 파이프라인은 자유 블록(actionBlocks),
            옛 저장분은 고정 필드(plan)로 그린다 */}
        {(decision.actionBlocks ?? []).length > 0 && (
          <section className={styles.scrollSection}>
            <div className={styles.scrollHead}>
              <span className={styles.scrollNum}>{nextNo()}</span>
              <span className={styles.scrollTitle}>그래서 지금 어떻게 할까</span>
            </div>
            {(decision.actionBlocks ?? []).map((block, i) => (
              <div key={i}>
                <div className={styles.prologueSub}>{block.subtitle}</div>
                <div className={styles.prologueBody}>{block.body}</div>
              </div>
            ))}
          </section>
        )}
        {!(decision.actionBlocks ?? []).length && plan && (
          <section className={styles.scrollSection}>
            <div className={styles.scrollHead}>
              <span className={styles.scrollNum}>{nextNo()}</span>
              <span className={styles.scrollTitle}>{plan.title}</span>
            </div>
            <div className={styles.answer}>{plan.stance}</div>
            {plan.answer && <div className={styles.mindset}>{plan.answer}</div>}
            {plan.timing && (
              <div className={styles.listBlock}>
                <div className={styles.listLabel}>언제</div>
                <div className={styles.listLine}>{plan.timing}</div>
              </div>
            )}
            {plan.nextMove && (
              <div className={styles.listBlock}>
                <div className={styles.listLabel}>다음 행동</div>
                <div className={styles.listLine}>{plan.nextMove}</div>
              </div>
            )}
            {/* 신호는 1막(판을 다시 봐야 하는 신호)으로 옮겼다 — 여기 또 실으면 이중이다 */}
            {plan.stopCondition && (
              <div className={styles.listBlock}>
                <div className={styles.listLabel}>여기서 멈춘다</div>
                <div className={styles.listLine}>{plan.stopCondition}</div>
              </div>
            )}
          </section>
        )}
      </div>
    );
  }

  const down = report.diagnosis
    .filter((d) => d.level.includes('불리'))
    .sort((a, b) => a.rank - b.rank);
  const up = report.diagnosis
    .filter((d) => d.level.includes('유리'))
    .sort((a, b) => a.rank - b.rank);
  // 중립은 방향 그룹에 못 들어가니 맨 뒤에 따로, 조용히 둔다. 원칙은 중립을 만들지 않는
  // 것(판정을 유리/불리로 확정)이고, 그래도 남은 중립은 숨기지 않고 보류로 밝힌다.
  const hold = report.diagnosis.filter((d) => d.level === '중립');
  // 숫자의 주범 먼저 — 확률이 낮으면 낮춘 것부터, 높으면 높인 것부터.
  const lowSide = probability == null || probability < 50;

  return (
    <div className={styles.scrollWrap}>
      {/* 01 결론 — 숫자, 총평, 무엇이 낮추고 올렸는지까지 판정 전부가 이 안에 있다 */}
      <section className={styles.scrollSection}>
        <div className={styles.scrollHead}>
          <span className={styles.scrollNum}>01</span>
          <span className={styles.scrollTitle}>먼저, 결론부터</span>
        </div>
        {probability != null && <Gauge probability={probability} />}
        {history.length >= 2 && <Trend history={history} />}
        {report.delayedRegret && <DelayedRegretMark mark={report.delayedRegret} />}
        <div className={styles.summary}>{report.diagnosisSummary}</div>
        <DiagnosisGroup
          title={lowSide ? '재회 가능성을 낮춘 것' : '재회 가능성을 높인 것'}
          tone={lowSide ? 'down' : 'up'}
          items={lowSide ? down : up}
          openKey={openKey}
          onToggle={toggle}
        />
        <DiagnosisGroup
          title={lowSide ? '재회 가능성을 높인 것' : '재회 가능성을 낮춘 것'}
          tone={lowSide ? 'up' : 'down'}
          items={lowSide ? up : down}
          openKey={openKey}
          onToggle={toggle}
        />
        <DiagnosisGroup
          title="아직 판단하지 못한 것"
          tone="hold"
          items={hold}
          openKey={openKey}
          onToggle={toggle}
        />
        {/* 변동내역 — 새 사실이 반영돼 지난 판정에서 달라진 것. 결정론 diff라 서술과 어긋나지 않는다 */}
        {delta && delta.factors.length > 0 && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>
              지난 분석에서 달라진 것 ({delta.probabilityFrom}% → {delta.probabilityTo}%)
            </div>
            {delta.factors.map((f) => (
              <div className={styles.listLine} key={f.name}>
                {f.name} {f.from} → {f.to}
              </div>
            ))}
          </div>
        )}
      </section>

      {/* 02 이별 분석 — 대제목은 고정 문구(역할 안내), 장의 제목은 훅 문장 하나뿐이다.
          강조는 장마다 하나만: 라벨, 제목, 굵은 답이 셋 다 서면 아무것도 안 선다 */}
      {report.analysisChapters.length > 0 && (
        <section className={styles.scrollSection}>
          <div className={styles.scrollHead}>
            <span className={styles.scrollNum}>02</span>
            {/* 섹션 제목은 백엔드가 확정한 값을 쓴다 — 여기 박아두면 지시를 고쳐도 화면이 안 바뀐다.
                옛 저장분엔 값이 없을 수 있어 그때만 현재 제목으로 받는다 */}
            <span className={styles.scrollTitle}>
              {report.analysisSectionTitle || '지금 상대는 어떤 마음일까'}
            </span>
          </div>
          {/* v5는 질문 하나에 답 하나. eyebrow/title/answer/psychology는 옛 저장분에만 있어
              그때 모양 그대로 그린다 — 안 그러면 예전 판독을 연 사람에게 빈 화면이 나간다 */}
          {report.analysisChapters.map((item, i) => (
            <div className={styles.chapterBlock} key={i}>
              {(item.question || item.eyebrow) && (
                <div className={styles.chapterAsk}>{item.question ?? item.eyebrow}</div>
              )}
              <div className={styles.chapterTitle}>{item.verdict ?? item.title}</div>
              {item.answer && <div className={styles.chapterLead}>{item.answer}</div>}
              <div className={styles.chapterBody}>{item.reading}</div>
              {item.psychology && (
                <div className={styles.principle}>
                  <div className={styles.principleLabel}>{item.psychology.concept}</div>
                  <div className={styles.principleText}>{item.psychology.reading}</div>
                </div>
              )}
            </div>
          ))}
          {/* baseline은 축을 미리 정하지 않고 한 덩이로 종합한다 */}
          {report.synthesis && <div className={styles.synthesis}>{report.synthesis}</div>}
          {/* 02의 끝점 — 감정, 선택, 회복 기대를 섞지 않고 세 줄로 세운다 */}
          {report.currentState && (
            <div className={styles.stateBlock}>
              <div className={styles.stateRow}>
                <span className={styles.stateLabel}>남은 감정</span>
                <span className={styles.stateText}>{report.currentState.feeling}</span>
              </div>
              <div className={styles.stateRow}>
                <span className={styles.stateLabel}>지금의 선택</span>
                <span className={styles.stateText}>{report.currentState.choice}</span>
              </div>
              <div className={styles.stateRow}>
                <span className={styles.stateLabel}>다시 해볼 수 있다는 기대</span>
                <span className={styles.stateText}>{report.currentState.repairBelief}</span>
              </div>
            </div>
          )}
        </section>
      )}

      {/* 03 행동 계획 — 병목에서 파생되고, 왜 다른 선택이 아닌지까지 말한다.
          baseline 모드는 02만 만들어서 이 섹션이 통째로 없다 */}
      {plan && (
      <section className={styles.scrollSection}>
        <div className={styles.scrollHead}>
          <span className={styles.scrollNum}>{report.analysisChapters.length > 0 ? '03' : '02'}</span>
          <span className={styles.scrollTitle}>{plan.title}</span>
        </div>
        <div className={styles.answer}>{plan.answer}</div>
        {/* 마음가짐은 결론 바로 밑에 붙인다 — 목록 항목으로 내리면 지켜야 할 규칙처럼 읽힌다 */}
        {plan.mindset && <div className={styles.mindset}>{plan.mindset}</div>}
        <div className={styles.listBlock}>
          <div className={styles.listLabel}>언제</div>
          <div className={styles.listLine}>{plan.timing}</div>
          {plan.whyThisTiming && <div className={styles.followWhy}>{plan.whyThisTiming}</div>}
        </div>
        {/* 기다림을 권한 판이면 끝나는 지점의 행동이 반드시 있어야 무기한 대기가 안 된다 */}
        {plan.nextMove && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>그때 할 것</div>
            <div className={styles.listLine}>{plan.nextMove}</div>
          </div>
        )}
        {plan.goal && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>이번 목표</div>
            <div className={styles.listLine}>{plan.goal}</div>
            {plan.decisionValue && <div className={styles.followWhy}>{plan.decisionValue}</div>}
          </div>
        )}
        {plan.doList.length > 0 && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>이렇게</div>
            {plan.doList.map((line, i) => (
              <div className={styles.listLine} key={i}>
                {line}
              </div>
            ))}
          </div>
        )}
        {plan.stopCondition && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>여기서 멈춘다</div>
            <div className={styles.listLine}>{plan.stopCondition}</div>
            {plan.ifClosed && <div className={styles.followWhy}>{plan.ifClosed}</div>}
          </div>
        )}
        {plan.avoid.length > 0 && (
          <div className={styles.listBlock}>
            <div className={styles.listLabel}>하지 않는다</div>
            {plan.avoid.map((line, i) => (
              <div className={styles.listLine} key={i}>
                {line}
              </div>
            ))}
          </div>
        )}
      </section>
      )}
    </div>
  );
}
