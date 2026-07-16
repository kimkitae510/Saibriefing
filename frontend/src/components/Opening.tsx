import { IntakeWizard, intakeSteps, recapLines, type IntakeDraft } from './IntakeWizard';
import styles from './Opening.module.css';

// 사연 하나가 시작될 때 거치는 오프닝: 질문 N개, 되짚기 한 장. 되짚기의 버튼이 곧 시작이다 —
// 이야기 칸은 없다. 무슨 일이 있었는지는 대화방에서 상담자의 첫 말(대본)이 청한다. 지피티와
// 경쟁앱 둘 다 채팅 안에서 시작하고, 칸을 뒀을 때보다 입구가 하나 적다.
// 첫 방문(IntroPage)과 새 이야기(NewStoryPage)가 같은 흐름을 쓴다 — 문진은 사연 단위라
// 방을 새로 만들 때마다 다시 받아야 하고, 두 화면이 따로 그리면 한쪽만 고쳐져 어긋난다.
// 단계 번호는 부모가 히스토리에 들고 있고(뒤로가기가 사이트 밖으로 안 나가게), 여기는
// 그 번호로 무엇을 그릴지만 정한다: 1~N 질문, N+1 되짚기.

export function recapPhaseOf(draft: IntakeDraft): number {
  return intakeSteps(draft).length + 1;
}

// 되짚기 화면의 한 줄. 판을 가르는 답 하나를 골라 그 뜻을 말한다 — 약속이 아니라 이 답이
// 무엇을 바꾸는지다. 문구는 임시.
function transitionHook(draft: IntakeDraft): string {
  if (draft.priorReunion === 'MANY' && draft.repeatBreakupPattern === 'SAME_ISSUE') {
    return '같은 문제로 두 번 이상 끊긴 판입니다. 연락이 닿는지보다 왜 같은 자리에서 어긋나는지가 먼저입니다.';
  }
  if (draft.contactMode === 'BLOCKED') {
    return '차단은 끝이 아니라 멈춤입니다. 끊기기 직전에 무엇이 있었는지가 이 판을 가릅니다.';
  }
  if (draft.clingReaction === 'CLUNG_WAVERED') {
    return '매달렸을 때 흔들린 상대입니다. 그 흔들림이 무엇이었는지가 판의 핵심입니다.';
  }
  return '여기까지가 판의 뼈대입니다. 살은 이야기가 채웁니다.';
}

export function Opening({
  phase,
  goPhase,
  goBack,
  draft,
  onDraft,
  starting,
  error,
  onStart,
}: {
  phase: number;
  goPhase: (next: number) => void;
  goBack: () => void;
  draft: IntakeDraft;
  onDraft: (patch: Partial<IntakeDraft>) => void;
  starting: boolean;
  error: string;
  onStart: () => void;
}) {
  const steps = intakeSteps(draft);
  if (phase >= 1 && phase <= steps.length) {
    return (
      <IntakeWizard
        step={phase - 1}
        draft={draft}
        onChange={onDraft}
        onNext={() => goPhase(phase + 1)}
        onBack={goBack}
      />
    );
  }

  const lines = recapLines(draft);
  return (
    <div className={styles.recapWrap}>
      <div className={styles.head}>
        <div className={styles.section}>정리</div>
        <h1 className={styles.title}>{lines.length ? '판이 잡혔습니다' : '이제 이야기를 들을 차례입니다'}</h1>
        <p className={styles.why}>{transitionHook(draft)} 무슨 일이 있었는지는 대화에서 묻습니다.</p>
      </div>
      {lines.length > 0 && (
        <dl className={styles.recap}>
          {lines.map(([k, v]) => (
            <div className={styles.recapRow} key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
        </dl>
      )}
      {error && <div className={styles.error}>{error}</div>}
      <div className={styles.foot}>
        <button className={styles.cta} onClick={onStart} disabled={starting}>
          {starting ? '시작하는 중…' : '이야기 시작하기'}
        </button>
        <button className={styles.quiet} onClick={goBack} disabled={starting}>
          이전
        </button>
      </div>
    </div>
  );
}
