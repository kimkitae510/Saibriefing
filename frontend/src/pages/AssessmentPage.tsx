import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { HelpModal } from '../components/HelpModal';
import { ReadingBook } from '../components/ReadingBook';
import { ReviewBlock } from '../components/ReviewBlock';
import {
  confirmBreakup,
  getAssessments,
  runAssessment,
  type AssessmentResponse,
} from '../api/assessment';
import { getPickedCases, pickCases, type PickedCases } from '../api/match';
import { getUsage } from '../api/usage';
import { addStoryFact, deleteStoryFact } from '../api/story';
import { createShare, getActiveShare, revokeShare } from '../api/share';
import { extractErrorCode, extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import { useGoPayment } from '../utils/paymentOrigin';
import styles from './AssessmentPage.module.css';
import { BRAND } from '../brand';

// 답변 칸 상한. 원장 한 줄이 200자인데 질문을 앞에 붙여 저장하므로, 남는 47자 안에서
// 질문을 줄인다 — 상한이 질문마다 달라지면 유저가 읽을 수 없는 숫자가 된다.
const ANSWER_MAX = 150;

// 게스트 잠금 미리보기의 장식 아크에만 쓴다(실데이터 게이지는 폐기됨)
const ARC_LEN = Math.PI * 120;

// 원장에 남길 한 줄. 답은 그대로 두고 넘치는 몫은 질문에서 던다 — 잘려야 할 쪽은
// 유저가 쓴 말이 아니라 우리가 붙인 꼬리표다.
function factLine(ask: string, answer: string): string {
  const room = 200 - answer.length - 3;
  if (room < 8) return answer;
  const label = ask.length > room ? `${ask.slice(0, room - 1)}…` : ask;
  return `${label} — ${answer}`;
}


/* 로딩/분석 중 점 애니메이션 — 일러스트(달) 대신 쓰는 유일한 장식 */
function Dots() {
  return (
    <span className={styles.stateDots} aria-hidden="true">
      <span className={styles.stateDot} />
      <span className={styles.stateDot} />
      <span className={styles.stateDot} />
    </span>
  );
}

/* 섹션 머리 — 선 장식 없이 제목 크기와 여백으로만 구획한다(그룹 카드가 경계를 대신 잡아준다).
   countClass: 신호 섹션의 개수를 그 섹션 점수 색과 맞출 때 쓴다 */
function SectionHead({
  title,
  count,
  countClass,
}: {
  title: string;
  count?: number;
  countClass?: string;
}) {
  return (
    <div className={styles.sectionHead}>
      <span className={styles.sectionTitle}>{title}</span>
      {count != null && (
        <span className={`${styles.sectionCount} ${countClass ?? ''}`}>{count}</span>
      )}
    </div>
  );
}

function BackBar({ onBack, onHelp }: { onBack: () => void; onHelp?: () => void }) {
  return (
    <div className={styles.topbar}>
      <button className={styles.backButton} onClick={onBack} aria-label="뒤로">
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
          <path d="M15 5l-7 7 7 7" stroke="#ebebee" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>
      <div className={styles.topTitle}>분석</div>
      {onHelp && (
        <button className={styles.helpButton} onClick={onHelp} aria-label="도움말">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none">
            <circle cx="12" cy="12" r="9" stroke="#98989f" strokeWidth="1.6" />
            <path d="M9.6 9.2a2.4 2.4 0 114.1 1.7c-.7.7-1.7 1.1-1.7 2.2M12 16.4h.01" stroke="#98989f" strokeWidth="1.7" strokeLinecap="round" />
          </svg>
        </button>
      )}
    </div>
  );
}

// 진행 중인 분석 호출을 컴포넌트 밖(모듈)에 붙잡아 둔다 — 라우팅으로 나갔다 와도 컴포넌트만
// 죽지 요청은 살아 있으므로, 재입장 시 같은 호출에 다시 붙어 스피너가 이어지고 결과도 받는다.
// 중복 실행(쿼터 이중 차감)도 여기서 막힌다: 돌고 있으면 새 POST를 만들지 않는다.
// 새로고침으로 모듈까지 날아간 판은 백엔드 인플라이트 잠금이 이중 실행을 거른다.
let inflightRun: { storyId: number; promise: Promise<AssessmentResponse> } | null = null;

function startOrJoinRun(storyId: number): Promise<AssessmentResponse> {
  if (inflightRun && inflightRun.storyId === storyId) {
    return inflightRun.promise;
  }
  const run = { storyId, promise: runAssessment(storyId) };
  inflightRun = run;
  const clear = () => {
    if (inflightRun === run) inflightRun = null;
  };
  run.promise.then(clear, clear);
  return run.promise;
}

// 쿨다운 3분은 초로만 적으면 "170초 후"처럼 읽어야 계산이 되는 숫자가 된다 — 분이 있으면 분으로.
function formatCooldown(seconds: number): string {
  if (seconds < 60) {
    return `${seconds}초`;
  }
  const rest = seconds % 60;
  return rest === 0 ? `${seconds / 60}분` : `${Math.floor(seconds / 60)}분 ${rest}초`;
}

export function AssessmentPage() {
  const { storyId: storyIdParam } = useParams();
  const storyId = Number(storyIdParam);
  const navigate = useNavigate();
  const goPayment = useGoPayment();

  const [result, setResult] = useState<AssessmentResponse | null>(null);
  // 직전 분석의 확률 — 게이지 옆 "지난 분석보다 ±N" 표시용. 번복(잠금 해제, 제안 철회) 뒤에는
  // 비교 기준이 흐려져서 null로 지운다(엉뚱한 증감이 뜨는 것보다 안 뜨는 게 낫다).
  const [, setPrevProb] = useState<number | null>(null);
  // 재진단 확률 이력(과거→현재). 2개 이상일 때만 판독 첫 장에 추세로 그린다 —
  // 실제 변화가 있을 때만 그래프가 값을 가진다.
  const [probHistory, setProbHistory] = useState<number[]>([]);
  const [loading, setLoading] = useState(true); // 진입 시 저장된 기록 조회(공짜 GET)
  const [diagnosing, setDiagnosing] = useState(false); // 새 분석(LLM 호출, 쿼터 차감) 실행 중
  const [error, setError] = useState('');
  // "이야기가 부족해요" 안내 — 에러 배너와 달리 스스로 사라지지 않는다.
  // 무엇을 더 말해야 하는지가 담겨 있어서, 유저가 읽고 뒤로가기로 나갈 때까지 떠 있어야 한다.
  const [notice, setNotice] = useState('');
  const [remaining, setRemaining] = useState<number | null>(null); // 남은 분석 횟수(이용권)
  const [isGuest, setIsGuest] = useState(false); // 게스트는 분석 잠금 — 계정 연결 유도
  // 대화가 한 줄도 없는 방에서 분석을 누른 경우(AS001). 실패가 아니라 순서가 뒤바뀐 것이라
  // 에러 배너 대신 "먼저 대화하기" 안내 화면으로 갈아탄다.
  const [noMessages, setNoMessages] = useState(false);
  const [showHelp, setShowHelp] = useState(false);
  // 사례 매칭. 진단이 뽑아둔 분류로 후보를 추리고 LLM이 본문을 읽어 고른다.
  const [picked, setPicked] = useState<PickedCases | null>(null);
  const [picking, setPicking] = useState(false);
  // 본문이 길어 기본은 접어두고, 펼친 것만 전문을 보여준다.
  const [openCase, setOpenCase] = useState<number | null>(null);
  // 질문에 답하는 칸의 입력값. 펼친 질문 하나만 쓰므로 상태도 하나면 된다.
  const [factInput, setFactInput] = useState('');
  const [factSaving, setFactSaving] = useState(false);
  // 펼쳐 둔 질문. 한 번에 하나만 연다 — 여러 칸이 동시에 열려 있으면 어디에 쓰는 중인지
  // 흐려지고, 세 질문의 답을 한 칸에 몰아 쓰던 예전 문제로 되돌아간다.
  const [openAsk, setOpenAsk] = useState<number | null>(null);
  // 이번 분석에서 답을 남긴 질문(질문 순번 → 답과 원장 줄 id). 분석이 새로 나오면 목록도
  // 새로 오므로 함께 비운다. id를 들고 있어야 지우기와 수정(교체)이 된다.
  const [answers, setAnswers] = useState<Record<number, { content: string; factId: number }>>({});
  const [confirming, setConfirming] = useState(false); // 헤어짐 확인 API 진행 중
  const [copied, setCopied] = useState(false); // 공유 시트가 없어 클립보드로 복사된 판의 피드백
  const [sharing, setSharing] = useState(false); // 공유 토큰 발급 중(연타 방지)
  // 살아 있는 공유 링크가 있는지. 있으면 "공유 중" 줄과 취소 버튼을 그린다 —
  // 끌 수 있다는 걸 화면이 말해줘야 유저가 마음 놓고 보낸다.
  const [shared, setShared] = useState(false);
  const [revoking, setRevoking] = useState(false);
  // 분석 생성이 실패했을 때 뜨는 재시도 패널. 스스로 사라지는 에러 배너와 달리, 유저가 누를
  // 때까지 남는다 — "다시 분석을 눌러 주세요"라고 시키는 대신 누를 것을 화면에 둔다.
  const [retryable, setRetryable] = useState(false);
  // 연속 실패 쿨다운의 남은 초(서버가 내려준 값에서 시작). 0이면 즉시 재시도 가능.
  const [cooldown, setCooldown] = useState(0);
  const aliveRef = useRef(true);

  // 에러 배너(쿼터 소진, 재분석 거부 등)가 화면에 계속 남지 않게 잠시 뒤 스스로 사라진다.
  useEffect(() => {
    if (!error) return;
    const timer = window.setTimeout(() => aliveRef.current && setError(''), 6000);
    return () => clearTimeout(timer);
  }, [error]);

  // 답변 완료 표시는 분석(createdAt)별로 localStorage에 남긴다 — 답 자체는 저장 즉시 서버
  // 원장에 들어가지만, 어느 질문에 답했는지는 화면 상태라 나갔다 오면 사라졌다(실측).
  // 질문 목록이 분석 결과에 붙어 오므로 같은 분석이면 순번도 같다 — 순번을 키로 쓸 수 있다.
  // 새 분석이 오면 키가 바뀌어 표시도 자연히 리셋된다(지난 질문의 완료 표시가 남으면
  // 엉뚱한 질문이 답변 완료로 보인다).
  const answersKey = (r: AssessmentResponse | null) =>
    `askAnswers:${storyId}:${r?.createdAt ?? ''}`;

  useEffect(() => {
    const saved: Record<number, { content: string; factId: number }> = {};
    try {
      const parsed: unknown = JSON.parse(localStorage.getItem(answersKey(result)) ?? '{}');
      if (parsed && typeof parsed === 'object') {
        for (const [k, v] of Object.entries(parsed)) {
          // 옛 포맷(문자열만 저장)은 버린다 — factId가 없으면 지우기/수정을 걸 수 없다
          if (v && typeof v === 'object' && typeof (v as { factId?: unknown }).factId === 'number') {
            saved[Number(k)] = v as { content: string; factId: number };
          }
        }
      }
    } catch {
      // 깨진 저장값은 없는 셈 친다
    }
    setAnswers(saved);
    setOpenAsk(null);
    setFactInput('');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result]);

  // 살아 있는 공유 링크가 있는지 확인한다. 재입장했을 때도 "공유 중"과 취소가 보여야
  // 유저가 언제든 끌 수 있다는 걸 안다 — 공유한 그 세션에서만 보이면 없는 것과 같다.
  useEffect(() => {
    if (result?.probability == null) return;
    getActiveShare(storyId)
      .then((token) => aliveRef.current && setShared(token != null))
      .catch(() => {}); // 부가 표시라 실패하면 조용히 안 그린다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result, storyId]);

  // 쿨다운 카운트다운. 매 초 setTimeout을 새로 거는 방식이라 interval이 어긋나 쌓이지 않고,
  // 0이 되면 재시도 버튼이 새로고침 없이 스스로 살아난다.
  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = window.setTimeout(() => aliveRef.current && setCooldown(cooldown - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  // 질문 하나에 답을 남긴다. 질문을 함께 저장하는 이유는 "없어요" 같은 답만 원장에 들어가면
  // 무엇에 대한 답인지 잃기 때문 — 다음 분석이 그 줄만 보고도 뜻을 알아야 한다.
  async function handleAnswer(index: number, ask: string) {
    const content = factInput.trim();
    if (!content || factSaving) return;
    setFactSaving(true);
    try {
      // 수정이면 이전 줄부터 지운다 — 옛 답과 새 답이 원장에 겹쳐 쌓이면 다음 분석이 둘 다 읽는다.
      // 이전 줄 삭제 실패(이미 지워짐 등)는 삼킨다 — 새 답을 남기는 게 본론이다.
      const before = answers[index];
      if (before) await deleteStoryFact(storyId, before.factId).catch(() => {});
      const factId = await addStoryFact(storyId, factLine(ask, content));
      if (aliveRef.current) {
        setAnswers((prev) => {
          const next = { ...prev, [index]: { content, factId } };
          localStorage.setItem(answersKey(result), JSON.stringify(next));
          return next;
        });
        setFactInput('');
        setOpenAsk(null);
      }
    } catch (e) {
      if (aliveRef.current) {
        setError(extractErrorMessage(e, '기록하지 못했습니다. 잠시 후 다시 시도해 주세요.'));
      }
    } finally {
      if (aliveRef.current) setFactSaving(false);
    }
  }

  async function handleConfirmBreakup() {
    setConfirming(true);
    try {
      // 서버가 오판이던 잠금 판정을 지우고 직전 확률 분석을 돌려준다 — 화면이 즉시 복귀한다.
      const res = await confirmBreakup(storyId);
      if (aliveRef.current) {
        setResult(res);
        setPrevProb(null);
        // 직전 확률 분석이 없으면(첫 분석부터 잠금) 빈 화면이 되는데, 맨 안내("기록이 없어요")로
        // 두면 번복이 무시된 것처럼 읽힌다 — 확인이 반영됐고 다음이 뭔지 말해준다.
        if (!res) {
          setNotice('헤어진 상태로 변경했습니다. 아래 분석 받기를 누르면 재회 가능성을 분석합니다.');
        }
        refreshUsage();
      }
    } catch (e) {
      if (aliveRef.current) setError(extractErrorMessage(e, '처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'));
    } finally {
      if (aliveRef.current) setConfirming(false);
    }
  }

  function refreshUsage() {
    getUsage()
      .then((u) => {
        if (!aliveRef.current) return;
        setRemaining(u.assessmentRemaining);
        setIsGuest(u.guest);
      })
      .catch(() => {});
  }

  // 결과 공유 — 공유 토큰을 받아 공개 결과 페이지 링크를 OS 공유 시트에 올린다. 카톡에
  // 붙이면 /s/{token}의 OG 태그가 미리보기 카드를 만들고, 받은 사람은 읽기 전용 결과
  // 화면으로 들어온다. 링크는 분석 1건에 1개(스냅샷)라 재공유는 같은 링크를 재사용하고,
  // 재분석 후 공유하면 새 링크가 나온다. 시트가 없는 데스크톱은 "요약 + 링크"를 클립보드에
  // 복사한다. 시트에서 취소하면 AbortError가 나는데 실패가 아니라 조용히 삼킨다.
  async function handleShare() {
    if (sharing || result?.probability == null) return;
    setSharing(true);
    try {
      const { token } = await createShare(storyId);
      setShared(true);
      const url = `${window.location.origin}/s/${token}`;
      const text = `${BRAND} 재회 가능성 분석 리포트`;
      if (navigator.share) {
        await navigator.share({ title: BRAND, text, url });
      } else {
        await navigator.clipboard.writeText(`${text}\n${url}`);
        setCopied(true);
        window.setTimeout(() => aliveRef.current && setCopied(false), 2000);
      }
    } catch {
      // 공유 취소 또는 토큰 발급 실패 — 결과 화면에 에러를 띄울 일이 아니다
    } finally {
      if (aliveRef.current) setSharing(false);
    }
  }

  // 공유 취소 — 이미 보낸 주소가 그 순간부터 안 열린다. 다시 공유하면 새 링크가 나가므로
  // 껐던 주소는 되살아나지 않는다.
  async function handleRevoke() {
    if (revoking) return;
    setRevoking(true);
    try {
      await revokeShare(storyId);
      setShared(false);
    } catch {
      // 이미 꺼져 있거나 방이 사라진 판 — 결과 화면에 에러를 띄울 일이 아니다
    } finally {
      if (aliveRef.current) setRevoking(false);
    }
  }

  // 새 분석은 버튼으로만 실행한다 — 페이지 진입만으로 일일 쿼터가 닳지 않게.
  async function diagnose() {
    setDiagnosing(true);
    setError('');
    setNoMessages(false); // 대화를 나누고 돌아왔을 수 있다 — 매 시도마다 다시 판단한다
    try {
      const res = await startOrJoinRun(storyId);
      if (aliveRef.current) {
        // 이야기 부족(INSUFFICIENT)은 결과가 아니라 안내다 — 기존 결과는 그대로 두고
        // 사라지지 않는 안내 배너로 띄운다(저장도 안 되는 임시 응답이라 결과 자리를 차지하면 안 된다).
        if (res.verdict === 'INSUFFICIENT') {
          // 쿨다운으로 막힌 응답은 안내가 아니라 재시도 대상이다 — 패널이 사유와 남은 시간을
          // 함께 보여주므로 같은 말을 배너로 또 띄우지 않는다.
          if (res.retryAfterSeconds) {
            setNotice('');
            setRetryable(true);
            setCooldown(res.retryAfterSeconds);
          } else {
            // 이야기 부족은 재시도가 답이 아니다(대화가 답) — 재시도 패널을 띄우지 않는다.
            setNotice(res.reason);
            setRetryable(false);
            setCooldown(0);
          }
        } else {
          setNotice('');
          setRetryable(false);
          setCooldown(0);
          // 새 결과로 갈아끼우기 전, 화면에 있던 확률이 이번 결과의 비교 기준이 된다.
          setPrevProb(result?.probability ?? null);
          setResult(res);
          // 방금 나온 확률도 추세에 잇는다 — 재진입해야 반영되면 "왜 안 늘지"가 된다.
          if (res.probability != null) {
            setProbHistory((prev) => [...prev, res.probability as number].slice(-5));
          }
          // 새 진단이라 매칭도 새로 돌려야 한다 — 저장은 진단 1건에 한 벌씩 묶인다.
          refreshPicked();
        }
        refreshUsage(); // 후차감이라 성공 시점에 갱신
      }
    } catch (e) {
      // 소진(Q001)을 백엔드 문구("이용권을 채우거나...")로 그대로 띄우면 아래 상시 '충전하기'와
      // 구매 권유가 이중이 된다 — 배너는 상태만 알리고, 동선은 링크 하나를 가리킨다(채팅과 동일 패턴).
      if (aliveRef.current) {
        const code = extractErrorCode(e);
        // L001(생성 실패)은 사라지는 배너로 처리하지 않는다. 유저가 할 일이 '다시 시도'인데
        // 6초 뒤 배너가 사라지면 무엇을 눌러야 할지가 화면에서 없어진다 — 재시도 패널로 넘긴다.
        if (code === 'L001') {
          setRetryable(true);
          setCooldown(0);
        } else if (code === 'AS001') {
          // 분석할 대화가 없음 — 유저가 뭘 잘못한 게 아니라 아직 할 차례가 아닌 것이다
          setNoMessages(true);
        } else {
          setError(
            code === 'Q001'
              ? '분석 횟수를 모두 사용했습니다. 아래 충전하기로 이어갈 수 있습니다.'
              : extractErrorMessage(e, '분석에 실패했습니다. 잠시 후 다시 시도해 주세요.'),
          );
        }
      }
    } finally {
      if (aliveRef.current) setDiagnosing(false);
    }
  }

  // 조회는 차감이 없다 — 이미 돌린 결과가 있으면 그대로 온다. 실패는 삼킨다:
  // 부속 정보라 못 불러왔다고 분석 화면에 에러를 띄울 일이 아니다.
  function refreshPicked() {
    getPickedCases(storyId)
      .then((res) => aliveRef.current && setPicked(res))
      .catch(() => {});
  }

  async function runPick() {
    setPicking(true);
    setError('');
    try {
      const res = await pickCases(storyId);
      if (aliveRef.current) setPicked(res);
    } catch (e) {
      if (aliveRef.current) {
        setError(extractErrorMessage(e, '사례를 찾지 못했습니다. 잠시 후 다시 시도해 주세요.'));
      }
    } finally {
      if (aliveRef.current) setPicking(false);
      refreshUsage();
    }
  }

  useEffect(() => {
    aliveRef.current = true;
    refreshUsage();
    refreshPicked();
    // 나갔다 온 사이에도 분석이 돌고 있으면 그 호출에 다시 붙는다 — 새 POST 없이
    // 진행 중 표시가 복원되고, 끝나는 순간 결과가 그대로 이 화면에 실린다.
    if (inflightRun && inflightRun.storyId === storyId) {
      diagnose();
    }
    // 진입 시엔 저장된 최신 분석만 보여준다. LLM 호출 없음.
    getAssessments(storyId)
      .then((all) => {
        if (!aliveRef.current) return;
        setResult(all[0] ?? null);
        // 비교 기준은 "직전의 확률 있는 분석" — 사이에 낀 잠금 판정(REUNITED)은 건너뛴다.
        setPrevProb(all.slice(1).find((a) => a.probability != null)?.probability ?? null);
        // 추세는 재진단이 쌓였을 때만 값이 있다 — 확률 있는 분석을 과거순으로.
        // 마지막 다섯 개만 본다: 그보다 길면 화면에서 한 줄로 안 읽힌다.
        setProbHistory(
          all
            .filter((a) => a.probability != null)
            .map((a) => a.probability as number)
            .reverse()
            .slice(-5),
        );
      })
      .catch((e) => aliveRef.current && setError(extractErrorMessage(e, '분석 기록을 불러오지 못했습니다.')))
      .finally(() => aliveRef.current && setLoading(false));
    return () => {
      aliveRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [storyId]);

  // 채팅에서 문진 답변을 마치고 넘어온 진입(?run=1) — 로딩이 끝나면 바로 분석을 시작한다.
  // 파라미터는 한 번 쓰고 지운다: 새로고침마다 분석이 다시 돌면 안 된다.
  useEffect(() => {
    if (loading) return;
    if (new URLSearchParams(window.location.search).get('run') === '1') {
      window.history.replaceState(null, '', window.location.pathname);
      diagnose();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading]);

  const toChat = () => navigate(`/stories/${storyId}`);

  // 실패/빈 화면에서도 남은 횟수가 보여야 한다(실측: 분석 실패 후 몇 회 남았는지 알 길이 없었음).
  // 실패는 후차감이라 차감되지 않는데, 그걸 확인할 방법이 이 표시다.
  const remainingHint =
    remaining != null ? (
      <div className={styles.stateHint}>
        남은 분석 <span className={styles.hintCountNum}>{remaining}회</span>
        <button className={styles.topupLink} onClick={goPayment}>
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M12 4.5v15M4.5 12h15" stroke="#B89DD1" strokeWidth="2.2" strokeLinecap="round" />
          </svg>
          충전하기
        </button>
      </div>
    ) : null;

  // 분석 생성 실패와 연속 실패 쿨다운의 공용 조각. 결과가 있을 때(배너 자리)와 없을 때(빈 화면)
  // 양쪽에서 같은 모양으로 쓰인다 — 실패 화면이 두 벌로 갈라지지 않게.
  // 채팅의 실패 처리와 같은 문법으로 맞춘다 — 큰 패널 + 채운 버튼은 실패를 사건처럼 키웠다.
  // 어두운 토스트에 아이콘 + 한 줄 사유, 행동은 작은 회색 칩. 두 화면의 실패가 같은 말로 읽힌다.
  const retryPanel = retryable ? (
    <div className={styles.retryPanel}>
      <svg className={styles.retryIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
        {cooldown > 0 ? (
          <>
            <circle cx="12" cy="12" r="9" stroke="#B89DD1" strokeWidth="1.6" />
            <path d="M12 7.2V12l3 1.8" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </>
        ) : (
          <>
            <circle cx="12" cy="12" r="9" stroke="#B89DD1" strokeWidth="1.6" />
            <path d="M12 8v5M12 15.8h.01" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" />
          </>
        )}
      </svg>
      <div className={styles.retryText}>
        <div className={styles.retryTitle}>
          {cooldown > 0
            ? `${formatCooldown(cooldown)} 후 다시 시도할 수 있습니다`
            : '분석을 만들지 못했습니다'}
        </div>
        <div className={styles.retryBody}>
          {cooldown > 0
            ? '분석을 만들지 못하는 상태가 이어지고 있습니다. 이번 분석은 차감되지 않았습니다'
            : '이번 분석은 차감되지 않았습니다'}
        </div>
        {/* 실패 재시도는 같은 판이다 — 질문을 다시 묻지 않고 마지막 질문을 재사용한다 */}
        <button className={styles.retryBtn} onClick={diagnose} disabled={cooldown > 0}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              d="M20 12a8 8 0 11-2.3-5.6M20 4v4h-4"
              stroke="#B89DD1"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          다시 시도
        </button>
      </div>
    </div>
  ) : null;

  if (loading || diagnosing) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>
            {diagnosing ? (
              <>
                <div className={styles.stateTitle}>이야기를 읽고 있습니다</div>
                <Dots />
                {/* 단계 표시 — 긴 대기를 "지금 무엇을 하는 중"으로 바꾼다(서버 폴링 값).
                    지난 단계는 문장이 남고, 아직인 단계는 흐리게 — 장식 없이 자리와 농도로만 */}
                {/* 1호출이 사라져 단계는 하나다 — 단계 목록 대신 지금 하는 일 한 줄 */}
                <div className={styles.progressSteps}>
                  <div className={styles.stepNow}>사연 전체를 깊게 판독하고 리포트를 쓰고 있어요</div>
                </div>
                {/* 분석 LLM이 느릴 때 이탈해도 손해가 아니라는 안내 — 결과는 저장돼 재진입 시 보인다 */}
                <div className={styles.stateBody}>
                  시간이 걸릴 수 있으며, 화면을 나가도 결과는 저장됩니다
                </div>
              </>
            ) : (
              <Dots />
            )}
          </div>
        </div>
      </PhoneFrame>
    );
  }

  // 결과가 아예 없을 때만 전체 화면 에러. 결과가 있으면 아래에서 배너로 보여준다(결과를 가리지 않게).
  if (error && !result) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>
            <div className={styles.stateBody}>{error}</div>
          </div>
          {remainingHint}
          <div className={styles.footer}>
            <button className={styles.btnGhost} onClick={toChat}>
              대화로
            </button>
            <button className={styles.btnPrimary} onClick={diagnose}>
              다시 분석 (1회 차감)
            </button>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  // 게스트는 분석이 잠겨 있다 — 계정 연결로 유도한다(분석 버튼 대신).
  // 문장 두 줄만 있던 빈 화면은 무엇이 열리는지가 안 보였다. 실제 결과 화면을 읽을 수
  // 없을 만큼 흐리게 깔아 형태만 보여주고, 그 위에서 여는 값을 말한다.
  if (isGuest) {
    // 체크 목록은 무엇을 받는지가 라벨 하나로 뭉개진다 — 이름과 그게 뭔지를 두 줄로 나눈다
    const perk = (title: string, sub: string) => (
      <div className={styles.lockPerk}>
        <div className={styles.lockPerkTitle}>{title}</div>
        <div className={styles.lockPerkSub}>{sub}</div>
      </div>
    );
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.lockWrap}>
            <div className={styles.lockPreview} aria-hidden="true">
              <div className={styles.gaugeWrap}>
                <svg width="280" height="150" viewBox="0 0 280 150">
                  <path d="M20,138 A120,120 0 0 1 260,138" fill="none" stroke="#2a2a2e" strokeWidth="11" strokeLinecap="round" />
                  <path
                    d="M20,138 A120,120 0 0 1 260,138"
                    fill="none"
                    stroke="#B89DD1"
                    strokeWidth="11"
                    strokeLinecap="round"
                    strokeDasharray={`${ARC_LEN * 0.55} ${ARC_LEN + 40}`}
                  />
                </svg>
              </div>
              <div className={styles.lockGhostCards}>
                {[0, 1, 2].map((i) => (
                  <div className={styles.lockGhostCard} key={i}>
                    <div className={styles.lockGhostHead}>
                      <span className={styles.lockGhostChip} />
                      <span className={styles.lockGhostTitle} />
                    </div>
                    <span className={styles.lockGhostLine} />
                    <span className={`${styles.lockGhostLine} ${styles.lockGhostLineShort}`} />
                  </div>
                ))}
              </div>
            </div>
            <div className={styles.lockScrim} aria-hidden="true" />
            <div className={styles.lockPanel}>
              <div className={styles.lockBadge} aria-hidden="true">
                {/* 몸통이 넓고 고리가 짧으면 자물쇠가 아니라 가방으로 읽힌다 —
                    몸통을 좁히고 고리를 세운 뒤 열쇠구멍을 찍어 못 알아볼 여지를 없앤다 */}
                <svg width="19" height="19" viewBox="0 0 24 24" fill="none">
                  <rect x="5.6" y="10.8" width="12.8" height="9.4" rx="2.4" stroke="#B89DD1" strokeWidth="1.8" />
                  <path d="M8.7 10.8V8.1a3.3 3.3 0 016.6 0v2.7" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" />
                  <circle cx="12" cy="15.5" r="1.15" fill="#B89DD1" />
                </svg>
              </div>
              <div className={styles.lockTitle}>계정을 연결하면 분석이 열려요</div>
              <div className={styles.lockSub}>
                대화는 그대로 이어지고, 분석 1회를 드려요
              </div>
              <div className={styles.lockPerks}>
                {perk('재회 가능성 등급', '지금까지 나눈 대화를 읽고 정해진 기준으로 등급을 매깁니다')}
                {perk('유리하게, 불리하게 작용한 요인', '무엇이 등급을 올리고 내렸는지 근거와 함께 짚습니다')}
                {perk('비슷한 실제 사례', '같은 구도의 사례가 재회에 성공했는지 실패했는지 보여줘요')}
              </div>
            </div>
          </div>
          <div className={styles.lockFooter}>
            <button className={styles.btnPrimary} onClick={() => navigate('/guest-link')}>
              분석 1회 받고 시작하기
            </button>
            <div className={styles.lockFoot}>카카오, 네이버로 바로 연결할 수 있습니다</div>
          </div>
        </div>
      </PhoneFrame>
    );
  }

  // 분석 기록이 아직 없음 — 여기서만 첫 분석을 시작한다.
  // 방금 "이야기 부족" 안내를 받았다면 기본 문구 대신 그 안내를 계속 보여준다(자동 소멸 없음).
  if (!result) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>
            {retryPanel ??
              (noMessages ? (
                // 대화가 한 줄도 없는데 분석을 누른 판. 서버 문구("분석할 대화 내용이 없습니다")를
                // 그대로 띄우면 처음 온 사람에겐 거절로만 읽힌다 — 무엇을 먼저 해야 하는지 말한다
                <>
                  <div className={styles.stateTitle}>먼저 이야기를 들려주세요</div>
                  <div className={styles.stateBody}>
                    분석은 나눈 대화를 읽고 계산합니다.
                    <br />
                    그 사람과 어떻게 헤어졌는지부터 들려주세요.
                  </div>
                </>
              ) : notice ? (
                <div className={styles.stateBody}>{notice}</div>
              ) : (
                <>
                  <div className={styles.stateTitle}>아직 분석 기록이 없습니다</div>
                  <div className={styles.stateBody}>
                    지금까지의 대화를 읽고 재회 가능성을 분석합니다
                    <br />
                    대화를 충분히 나눌수록 정확해져요
                  </div>
                </>
              ))}
          </div>
          {!noMessages && remainingHint}
          {/* 재시도 패널이 떠 있으면 그 안의 버튼이 유일한 동선이다 — 같은 일을 하는 버튼을
              하단에 또 두면 쿨다운 중 비활성 버튼과 활성 버튼이 나란히 보인다 */}
          {!retryPanel && (
            <div className={styles.footer}>
              {/* 대화가 없으면 분석 버튼은 누를 때마다 같은 거절만 받는다 — 할 수 있는 일로 바꾼다 */}
              {noMessages ? (
                <button className={styles.btnPrimary} onClick={toChat}>
                  대화하러 가기
                </button>
              ) : (
                <button className={styles.btnPrimary} onClick={diagnose}>
                  분석 받기
                </button>
              )}
            </div>
          )}
        </div>
      </PhoneFrame>
    );
  }

  // "계속 대화하면 분석도 따라 갱신된다"는 오해가 있어, 이 결과가 언제 것인지 명시한다.
  const metaDate = result.createdAt ? formatListTime(result.createdAt) : '방금';

  // INSUFFICIENT는 저장되지 않고 diagnose()에서 배너로 처리되므로 여기 도달하는 결과는
  // POSSIBLE(확률), REUNITED(잠금 — 게이지 대신 전용 화면)뿐이다.
  // DATING 상태는 폐지 — 만나는 중 사연은 게이트가 INSUFFICIENT 안내로 처리한다.
  const reunited = result.verdict === 'REUNITED';
  const locked = reunited;
  // 요인, 유형, 점프, 게이지 계산은 폐기 — 판은 판독(decision)이 말한다.
  const asks: string[] = result.unansweredQuestions ?? [];
  // 정밀 판독 — 확률 있는 일반 판정에만 붙는다(백엔드가 그렇게만 생성).
  // 판독 구조가 아직 바뀌는 중이라, 서버가 옛 형식의 본문을 내려보낼 수 있다. 리포트 하나가
  // 화면 전체를 날리지 않게(실측: 빈 화면) 그릴 수 있는 모양인지 확인하고 통과시킨다.
  // 다른 구조로 저장된 본문에는 analysisChapters가 없다 — 확인하지 않으면 옛 판독이 화면을
  // 통째로 날린다(실측: 구조 개편 뒤 옛 본문에서 렌더 중 예외).
  // 행동 계획은 필수가 아니다 — baseline 모드는 02만 만들고 03을 만들지 않는다.
  const candidate = !locked ? (result.reading ?? null) : null;
  // 그릴 수 있는 본문인지: 새 구조는 판정 카드(verdictBlocks)가 본체고 mind와 분석 장은
  // 카드가 흡수해 비는 게 정상이다 — mind를 필수로 걸면 새 판독이 전부 실패로 보인다(실측).
  // 옛 저장분은 분석 블록(prologueBlocks)이나 심층 장(analysisChapters)으로 판정한다.
  const d = candidate?.report?.decision;
  const hasBody =
    (d?.verdictBlocks?.length ?? 0) > 0
    || (d?.prologueBlocks?.length ?? 0) > 0
    || Boolean(candidate?.report?.analysisChapters?.length);
  const reading = d && hasBody ? candidate : null;
  // 판독이 붙어야 하는 판인데 판독이 없다 — 실패했거나 구조 개편 전 본문이다.
  // 진단만으로 그리던 구화면은 폐기됐다: 반쪽을 결과처럼 보여주면 안 된다.
  // 실패는 무차감이라(백엔드 후차감) 다시 분석이 손해가 아니다.
  if (!locked && !reading) {
    return (
      <PhoneFrame>
        <div className={styles.wrap}>
          <BackBar onBack={toChat} />
          <div className={styles.state}>
            {retryPanel ?? (
              <>
                <div className={styles.stateTitle}>분석이 완성되지 못했어요</div>
                <div className={styles.stateBody}>
                  심층 판독까지 만들지 못해 결과를 보여드리지 않아요.
                  <br />
                  이 분석은 차감되지 않았어요.
                </div>
              </>
            )}
          </div>
          {remainingHint}
          {!retryPanel && (
            <div className={styles.footer}>
              <button className={styles.btnGhost} onClick={toChat}>
                대화로
              </button>
              <button className={styles.btnPrimary} onClick={diagnose}>
                다시 분석
              </button>
            </div>
          )}
        </div>
      </PhoneFrame>
    );
  }
  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <BackBar onBack={toChat} onHelp={() => setShowHelp(true)} />
        {error && (
          <div className={styles.errorBanner}>
            <svg className={styles.noticeIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="#D88B9F" strokeWidth="1.6" />
              <path d="M12 8v5M12 15.8h.01" stroke="#D88B9F" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            {error}
          </div>
        )}
        {notice && (
          <div className={styles.noticeBanner}>
            <svg className={styles.noticeIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="#B89DD1" strokeWidth="1.6" />
              <path d="M12 11v5M12 7.6h.01" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            {notice}
          </div>
        )}
        {retryPanel}
        <div className={styles.body}>
          <div className={styles.meta}>마지막 분석 {metaDate}</div>

          {/* 재회 성공은 확률 화면이 아니다 — 게이지 대신 히어로 문법(같은 결)으로.
              게이지에 반투명 덮개를 씌우던 잠금은 미완성 화면처럼 읽혔다(실측) */}
          {reunited ? (
            <div className={styles.reunitedHero}>
              <div className={styles.reunitedTitle}>다시 만나게 되었습니다</div>
              <div className={styles.reunitedSub}>
                재회에 성공해 분석은 여기까지입니다.
                <br />
                이제 관계를 이어가는 대화로 함께합니다.
              </div>
            </div>
          ) : null}

          {reunited ? (
            <>
              {result.reason && <div className={styles.datingReason}>{result.reason}</div>}
              {/* 재회 후 다시 헤어질 수도, 재회로 오해했을 수도 있다 — 누르면 즉시 확률로 복귀 */}
              <div className={styles.lockCard}>
                <div className={styles.lockTitle}>혹시 다시 헤어지게 됐다면</div>
                <div className={styles.lockAskRow}>
                  <span className={styles.lockAskText}>
                    다시 헤어졌거나 분석이 잘못 판단한 경우 알려 주세요. 분석을 다시 엽니다.
                  </span>
                  <button
                    className={styles.lockConfirmBtn}
                    onClick={handleConfirmBreakup}
                    disabled={confirming}
                  >
                    {confirming ? '반영 중…' : '헤어진 것이 맞습니다'}
                  </button>
                </div>
              </div>
            </>
          ) : null}

          {/* 정밀 판독 — 결론부터 행동 계획까지 세로 스크롤 문서 하나로 펼친다.
              key는 판독이 바뀌면(재분석) 펼침 상태를 처음으로 되돌리기 위한 것 */}
          {reading && (
            <ReadingBook
              key={result.createdAt ?? 'reading'}
              reading={reading}
              probability={result.probability}
              history={probHistory}
            />
          )}

          {/* 공유는 확률 결과에서만 — 잠금 판정(사귀는 중, 재회 성공)은 남에게 보일 내용이
              아니다. 판독 아래에 둔다: 결론보다 공유 버튼이 먼저 나오면 읽기 전에 뿌리라는
              화면이 된다 */}
          {!locked && result.probability != null && (
            <div className={styles.shareRow}>
              <button className={styles.shareBtn} onClick={handleShare} disabled={sharing}>
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    d="M12 14.5V4M8.2 7.3L12 3.5l3.8 3.8"
                    stroke="#B89DD1"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                  <path
                    d="M6 11.5v8.5h12v-8.5"
                    stroke="#B89DD1"
                    strokeWidth="2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
                {copied ? '링크를 복사했어요' : '공유하기'}
              </button>
              {/* 링크를 아는 사람이면 누구나 열리는 주소라(로그인이 없다), 무엇이 보이고
                  되돌릴 수단이 있다는 걸 보내기 전에 말해준다. 공유 페이지는 이 화면과 같은
                  판독을 그리므로(근거 문장 포함) 그 사실을 여기서 밝힌다 — 잘라내 보여주면
                  "왜 다 안 보이냐"가 되고, 밝히지 않으면 "이렇게까지 보일 줄 몰랐다"가 된다.
                  공유 중일 때는 문구가 바뀌고 끄는 버튼이 붙는다. 취소를 버튼 아래 글자로
                  두는 건 권하는 동작이 아니어서다(질문 카드의 건너뛰기와 같은 문법) */}
              {shared ? (
                <div className={styles.shareNote}>
                  링크로 공유 중이에요
                  <button className={styles.shareRevoke} onClick={handleRevoke} disabled={revoking}>
                    {revoking ? '취소하는 중…' : '공유 취소'}
                  </button>
                </div>
              ) : (
                <div className={styles.shareNote}>
                  링크를 받은 사람은 이 화면의 판단 근거까지 그대로 볼 수 있어요. 언제든 취소하면
                  링크가 막혀요
                </div>
              )}
            </div>
          )}

          {/* 비슷한 사례 — 확률 대역이 구성의 상한을 정하고(낮으면 재회한 사례 1 + 못 한 사례 1,
              높으면 재회한 사례 2) 실제 장수는 고른 쪽이 정한다. 결과에 색을 입히지 않는 원칙은
              그대로다: 초록은 좋음, 회색은 나쁨으로 읽혀 남의 결말에 등급이 붙는다 */}
          {result && picked && (
            <>
              {/* 판독 뒤에 붙는 에필로그 — 판독의 질문 문법으로 제목을 잇는다 */}
              <SectionHead title={reading ? '나와 비슷한 관계는 어떻게 됐을까' : '비슷한 사례'} />

              {picked.locked && (
                <div className={styles.caseRunWrap}>
                  <div className={styles.caseEmpty}>
                    이별 사유와 상황이 닮은 사례를 찾아 드립니다. 어떤 점이 겹치는지와, 그 사례가
                    왜 그렇게 됐는지도 함께 읽어 드려요.
                  </div>
                  <button className={styles.caseRunBtn} onClick={runPick} disabled={picking}>
                    {picking ? '사례를 찾는 중…' : '비슷한 사례 찾기'}
                  </button>
                </div>
              )}

              {!picked.locked && picked.cases.length > 0 && (
                <>
                  {/* 카드를 가로질러 읽는 한 줄. 카드가 한 장이거나 해설을 못 붙인 판에선 안 온다 */}
                  {picked.summary && <div className={styles.caseSummary}>{picked.summary}</div>}
                  {/* 목록 앞의 프레임은 한 줄로 끝낸다 — 남의 이야기에 닿기 전 자리라 길수록
                      본문이 밀린다. 태그 뜻풀이는 뺐다: 카드마다 "겹치는 지점" 해설이 붙어 있어
                      같은 말을 두 번 하는 셈이었다 */}
                  <div className={styles.caseNote}>실제 사례들을 요약한 글입니다.</div>
                  <div className={styles.caseList}>
                    {picked.cases.map((c) => {
                      const open = openCase === c.id;
                      return (
                        <div className={styles.caseItem} key={c.id}>
                          <div className={styles.caseOutcome}>
                            {c.outcome === '성공' ? '재회 성공' : '재회 실패'}
                          </div>
                          {/* 기록 보관소 문법의 메타 줄 — 미상도 표기한다(기록엔 빈칸이 정상).
                              만난 기간은 안 싣는다: 겹치면 아래 태그가 값으로 띄우고, 안 겹치는데
                              떠 있으면 3개월 만난 사람이 3년 사례를 보며 자기 연애를 저울질한다 */}
                          <div className={styles.caseMeta}>
                            {[c.gender ?? '성별 미상', c.ageGroup ?? '나이 미상'].join(' / ')}
                          </div>
                          {/* 겹친 지점만 태그가 된다 — 뜨는 값은 전부 유저가 이미 말한 것이다.
                              결말 라벨과 맞닿으면 태그가 결말의 원인으로 읽혀 예언이 되므로
                              본문 바로 위에 둔다 */}
                          {c.matchedTags.length > 0 && (
                            <div className={styles.caseTags}>
                              {c.matchedTags.map((t) => (
                                <span className={styles.caseTag} key={t}>
                                  #{t}
                                </span>
                              ))}
                            </div>
                          )}
                          <div className={open ? styles.caseStory : styles.caseStoryClamped}>
                            {c.story}
                          </div>
                          {/* 사례 본문과 해설 사이에 선을 긋는다 — 경계가 없으면 서비스가 붙인
                              읽기가 남의 기록의 일부로 읽힌다 */}
                          {(c.similarity || c.reading) && (
                            <div className={styles.caseReading}>
                              {c.similarity && (
                                <div className={styles.caseReadingRow}>
                                  <span className={styles.caseReadingLabel}>겹치는 지점</span>
                                  {c.similarity}
                                </div>
                              )}
                              {c.reading && (
                                <div className={styles.caseReadingRow}>
                                  <span className={styles.caseReadingLabel}>이 사례를 읽자면</span>
                                  {c.reading}
                                </div>
                              )}
                            </div>
                          )}
                          <button
                            className={styles.caseMore}
                            onClick={() => setOpenCase(open ? null : c.id)}
                          >
                            {open ? '접기' : '더 보기'}
                          </button>
                        </div>
                      );
                    })}
                  </div>
                </>
              )}

              {/* 이유를 갈라 말한다 — "대화를 더 해달라"와 "데이터가 아직 부족하다"는 다른 말이다 */}
              {!picked.locked && picked.cases.length === 0 && (
                <div className={styles.caseEmpty}>
                  {picked.emptyReason === 'NO_PROFILE'
                    ? '어쩌다 헤어졌는지 대화에서 더 들려주면 비슷한 사례를 찾아드려요.'
                    : '아직 닮은 사례를 찾지 못했습니다.'}
                </div>
              )}
            </>
          )}

          {/* 근거가 없어 중립으로 남은 요인 — 채워달라는 요청으로 뒤집어 다음 대화를 유도한다.
              채팅에서 말하면 다음 분석에 반영된다. 맨 아래 배치 — 판독이 먼저,
              다음 분석을 위한 요청은 마지막이 자연스러운 독서 순서다 */
          }
          {/* 폼에 적으면 원장에 쌓이고 그것만으로 재분석 가드가 열린다(대화 횟수 차감 없음) */}
          {!locked && asks.length > 0 && (
            <>
              {/* "앞으로 지켜볼 것"은 내렸다 — 상대가 먼저 연락하면 유리하다는 건 유저가 이미
                  아는 얘기라 자리값을 못 했고, 그 일이 실제로 생기면 대화에서 말하게 되어
                  다음 분석의 요인 카드로 잡힌다(서버는 계속 만든다, 화면에서만 뺀 것).
                  묻는 목록과 적는 칸은 한 상자에 둔다 — 묻고 답하는 한 쌍이라 갈라놓을 이유가 없다 */}
              <SectionHead title="알려주시면 분석이 더 정확해져요" />
              <div className={styles.askNote}>대화 횟수는 차감되지 않고, 답변은 다음 분석에 반영됩니다.</div>
              <div className={styles.dedList}>
                {asks.map((ask, i) => {
                  const done = answers[i] != null;
                  const open = openAsk === i;
                  return (
                    <div className={styles.dedItem} key={i}>
                      {/* 답은 접어둔다 — 펼쳐 두면 화면이 내가 쓴 글로 덮이고,
                          다시 눌러 고칠 수 있다는 것도 안 읽힌다 */}
                      <button
                        type="button"
                        className={styles.askTop}
                        onClick={() => {
                          setFactInput(open ? '' : (answers[i]?.content ?? ''));
                          setOpenAsk(open ? null : i);
                        }}
                        aria-expanded={open}
                      >
                        <div className={styles.dedSignal}>{ask}</div>
                        {done ? (
                          <span className={styles.askDone}>
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none">
                              <path d="M4 12.5l5 5 11-11" stroke="currentColor" strokeWidth="2.4"
                                    strokeLinecap="round" strokeLinejoin="round" />
                            </svg>
                            답변 완료
                          </span>
                        ) : (
                          <span className={styles.askChevron} aria-hidden="true">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none">
                              <path d="M9 18l6-6-6-6" stroke="currentColor" strokeWidth="2"
                                    strokeLinecap="round" strokeLinejoin="round" />
                            </svg>
                          </span>
                        )}
                      </button>
                      {open && (
                        <div className={styles.askForm}>
                          <textarea
                            className={styles.factInput}
                            value={factInput}
                            onChange={(e) => setFactInput(e.target.value)}
                            placeholder="답변 입력"
                            rows={2}
                            maxLength={ANSWER_MAX}
                            autoFocus
                          />
                          <div className={styles.factFormRow}>
                            {/* 평소엔 안 보인다 — 한참 남은 숫자는 읽을 이유가 없고 칸만 시끄럽다 */}
                            <span className={styles.factCount}>
                              {factInput.length > ANSWER_MAX * 0.7
                                ? `${factInput.length}/${ANSWER_MAX}`
                                : ''}
                            </span>
                            <button
                              className={styles.factSubmit}
                              disabled={!factInput.trim() || factSaving}
                              onClick={() => handleAnswer(i, ask)}
                            >
                              {factSaving ? '남기는 중' : done ? '수정' : '남기기'}
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </>
          )}

          {/* 분석 평가 — 판독을 다 읽은 뒤가 평가할 수 있는 시점이라 맨 아래.
              잠금 판정(REUNITED)도 평가 대상이다: 오판이면 그게 골든셋 재료다 */}
          <ReviewBlock storyId={storyId} resultKey={result.createdAt ?? ''} onRewarded={refreshUsage} />

        </div>

        {/* 잔여 줄은 body(스크롤) 밖에 둬서 스크롤과 무관하게 하단에 고정한다 — 채팅처럼 항상 보이게 */}
        <>
        <div className={styles.hintRow}>
          <div className={styles.hintCount}>
            {/* 무료/이용권 각각 보여주되 숫자만 밝게(채팅 잔여 줄과 같은 문법) */}
            {remaining != null ? (
              <>
                남은 분석 <span className={styles.hintCountNum}>{remaining}회</span>
              </>
            ) : (
              '이용권 없음'
            )}
          </div>
          {/* 소진 전에도 구매 위치가 보이게 상시 진입점 — 채팅의 충전하기와 같은 동선 */}
          <button className={styles.topupLink} onClick={goPayment}>
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M12 4.5v15M4.5 12h15" stroke="#B89DD1" strokeWidth="2.2" strokeLinecap="round" />
            </svg>
            충전하기
          </button>
        </div>

        <div className={styles.footer}>
          <button className={styles.btnGhost} onClick={() => navigate(`/stories/${storyId}/history`)}>
            기록
          </button>
          {/* 쿨다운 중엔 여기서도 막는다 — 위 패널은 비활성인데 아래로 우회되면 서버만 거절하고
              화면은 왜 안 되는지 말해주지 않는 상태가 된다 */}
          <button className={styles.btnPrimary} onClick={diagnose} disabled={cooldown > 0}>
            다시 분석 (1회 차감)
          </button>
        </div>
        </>

        {showHelp && (
          <HelpModal
            title="분석 가이드"
            onClose={() => setShowHelp(false)}
            sections={[
              {
                heading: '재회 가능성',
                text: '대화에서 확인된 상대의 마음과 행동, 두 사람의 관계를 근거로, 이 관계가 실제로 다시 시작될 가능성을 다섯 단계로 판정합니다. 재회를 바라고 오신 것을 전제로 가능성을 예측하는 것이지, 재회를 권하거나 말리는 판단이 아닙니다. 다시 다가갈지, 언제 어떻게 움직일지는 행동 계획과 대화에서 함께 정합니다.',
              },
              {
                heading: '등급을 믿어도 되나요',
                text: '들려주신 이야기 안에서의 판단입니다. 말하지 않은 사실은 반영되지 않고, 새로운 일이 생기거나 더 들려주신 뒤 다시 분석하면 판정도 다시 계산됩니다. %숫자는 쓰지 않습니다 — 사람 관계는 1% 단위로 잴 수 있는 것이 아니라서, 정직한 해상도인 다섯 단계로만 말합니다.',
              },
              {
                heading: '판을 만든 이유',
                text: '등급 아래에 그 판정을 만든 이유들이 근거와 함께 붙습니다. 판을 정한 쪽 이유가 먼저 오고, 반대 방향 근거가 실제로 있으면 그 이유도 함께 보여드립니다. 이야기에 근거가 없는 항목은 카드로 만들지 않습니다.',
              },
              {
                heading: '알려주시면 정확해져요',
                text: '분석이 물었는데 답이 없던 질문이 있으면 아래에 모아 둡니다. 대화에서 말해도 되고, 그 카드의 입력칸에 한 줄로 적어두셔도 다음 분석에 반영됩니다. 적는 것은 대화 횟수가 차감되지 않습니다.',
              },
              {
                heading: '분석 횟수',
                text: '분석은 이용권에서 1회씩 차감됩니다. 이야기가 부족하다는 안내만 받은 경우에는 차감되지 않습니다.',
              },
            ]}
          />
        )}
      </div>
    </PhoneFrame>
  );
}
