import { Fragment, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { BusinessInfo } from '../components/BusinessInfo';
import { SocialLogin } from '../components/SocialLogin';
import {
  EMPTY_DRAFT,
  IntakeWizard,
  draftToIntake,
  hasAnyAnswer,
  intakeSteps,
  recapLines,
  type IntakeDraft,
} from '../components/IntakeWizard';
import { guestStart } from '../api/auth';
import { tokenStore } from '../api/tokenStore';
import { createStory } from '../api/story';
import { putIntake } from '../api/intake';
import { extractErrorMessage } from '../api/client';
import styles from './IntroPage.module.css';
import { BRAND } from '../brand';

// 첫 화면이 로그인 폼이면 이게 뭐 하는 서비스인지 전달이 하나도 안 된다. 이별 서비스는
// 충동적으로 들어오는데 폼이 뜨면 그 자리에서 나간다. 그래서 첫 화면을 대화 시작점으로 둔다.
//
// 계정은 여기서 만들지 않는다 — 진입만으로 계정을 파면 크롤러가 긁을 때마다 계정이 생긴다.
// 실제로 말을 걸었을 때, 즉 첫 전송 시점에 만든다. 질문 단계의 답도 그때까지 화면이 들고
// 있다가 한 번에 보낸다(위저드를 앞에 세운 덕에 크롤러는 오히려 더 못 들어온다).
//
// 화면은 한 자리에서 단계만 바뀐다: 0 첫 화면, 1~N 질문, N+1 되짚기, 그 뒤가 사연 쓰기.
// N은 답에 따라 늘고 준다(가지 질문) — 그래서 단계 번호를 상수로 박지 않고 질문 목록
// 길이로 판정한다. 첫 화면에 적는 개수는 가지 없는 기본 길이다.
//
// 첫 화면은 게임 오프닝의 문법을 빌린다: 검은 바탕에 문장 하나, 답하면 다음. 캐릭터도
// 말풍선도 없이 화면 전체가 말을 건다. 다만 문장은 "이야기를 들어드린다"가 아니라
// "상황을 넣으면 판정과 할 일이 나온다"여야 한다 — 결제 심사가 이 화면만 보고 상담 대행이
// 아니라 리포트를 내는 소프트웨어로 읽어야 하고, 실제로 그 문구 때문에 세 번 반려됐다.
// 훅 문장은 임시다. 문구는 따로 확정한다.
const BASE_STEPS = intakeSteps(EMPTY_DRAFT).length;
const HOOK = '보내려던 그 메시지,\n판을 읽고 보내도 늦지 않습니다';
const HOOK_WHY =
  '이별까지의 상황을 넣으면 소프트웨어가 되는 판인지, 무엇 때문인지, 지금 할 일이 무엇인지를 리포트로 냅니다. 안 되는 판이면 안 된다고도 말합니다.';

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

export function IntroPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [draft, setDraft] = useState<IntakeDraft>(EMPTY_DRAFT);
  const [input, setInput] = useState('');
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');

  // 단계를 화면 state가 아니라 히스토리에 둔다 — state로 들고 있으면 질문 도중에 뒤로가기를
  // 누른 사람이 이전 질문이 아니라 사이트 밖으로 나간다. 라우터를 거쳐 밀어야 라우터가 쥐고
  // 있는 히스토리 인덱스와 어긋나지 않는다(직접 pushState하면 그게 덮인다).
  const phase = (location.state as { introPhase?: number } | null)?.introPhase ?? 0;
  const steps = intakeSteps(draft);
  const PHASE_RECAP = steps.length + 1;
  const goPhase = (next: number) => navigate('.', { state: { introPhase: next } });
  // 이전은 새 칸을 밀지 않고 되감는다 — 밀면 뒤로가기가 지나온 자리를 다시 밟아 맴돈다.
  const goBack = () => navigate(-1);

  // 토큰을 들고 온 재방문자는 인사도 질문도 다시 볼 이유가 없다 — 보던 대화로 바로 보낸다
  if (tokenStore.getAccess()) return <Navigate to="/stories" replace />;

  async function handleStart() {
    const content = input.trim();
    if (!content || starting) return;
    setStarting(true);
    setError('');
    try {
      await guestStart();
      const story = await createStory();
      // 접수는 사연에 딸린 부가 정보다 — 저장이 실패해도 사연까지 막지 않는다.
      // 하나도 안 채웠으면 아예 안 보낸다: 전부 null로 덮으면 서버가 "냈다"로 표시해,
      // 나중에 물어야 할 자리에서 이미 받은 것으로 읽힌다.
      if (hasAnyAnswer(draft)) {
        await putIntake(story.id, draftToIntake(draft)).catch(() => {});
      }
      // 첫 문장을 다시 치게 하면 시작이 두 번이 된다 — 채팅 화면이 받아서 그대로 보낸다
      navigate(`/stories/${story.id}`, { replace: true, state: { prefill: content, autoSend: true } });
    } catch (e) {
      setError(extractErrorMessage(e, '시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'));
      setStarting(false);
    }
  }

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        {/* 소개 화면은 캐릭터가 말을 거는 대화창이 아니라 무엇을 파는지 밝히는 자리다.
            결제 대행사 심사는 이 화면만 보고 제품을 분류하는데, 캐릭터와 말풍선이 먼저 뜨면
            리포트를 파는 소프트웨어가 아니라 대화 상대를 파는 것으로 읽힌다.
            캐릭터는 '이야기 시작하기' 뒤 대화방에서 그대로 만난다 */}
        {phase === 0 && (
          <>
            <div className={styles.logo}>{BRAND}</div>
            <div className={styles.hookWrap}>
              <div className={styles.mark} />
              <h1 className={styles.hook}>{HOOK}</h1>
              <p className={styles.hookWhy}>{HOOK_WHY}</p>
              <button className={styles.cta} onClick={() => goPhase(1)}>
                시작하기
              </button>
              <div className={styles.composerLabel}>짧은 질문 {BASE_STEPS}개 정도입니다</div>
            </div>

            {error && <div className={styles.error}>{error}</div>}

            <div className={styles.startBlock}>
              {/* 질문을 다 넘겨야 쓸 칸이 나오면, 당장 쏟아내려고 들어온 사람은 그 전에 나간다.
                  탈출구를 열어둔다 — 안 물어서 잃는 것보다 말도 못 하고 나가는 게 크다 */}
              <button className={styles.skipAsk} onClick={() => goPhase(PHASE_RECAP + 1)}>
                바로 이야기부터 할게요
              </button>
            </div>

            {/* 랜딩을 없애면 다른 기기에서 온 회원은 자기 대화가 사라진 걸로 본다 — 입구를 여기 둔다.
                글자 링크로 한 번 더 넘기는 대신 심볼을 바로 놓아 재방문자의 홉을 하나 줄인다.
                선을 하나 긋는다 — 위는 처음 온 사람의 자리, 아래는 이미 계정이 있는 사람의 자리다.
                구분이 없으면 소셜 아이콘이 '시작하기'의 다른 방법처럼 읽힌다 */}
            <div className={styles.divider} />
            <SocialLogin variant="icon" onError={setError} />

            {/* 결제 대행사 심사와 전자상거래법이 요구하는 상시 링크. 심사자는 계정을 만들지 않고
                이 화면만 보므로, 로그인 뒤에만 있으면 없는 것과 같다. 히어로를 해치지 않게
                로그인 화면과 같은 결로 아래에 조용히 둔다.
                질문 단계에는 안 싣는다 — 초기화면은 여기고, 답하는 중에 약관이 깔려 있으면 소음이다 */}
            <div className={styles.docLinks}>
              {/* 라벨에 "환불"을 넣어둔다 — 심사자는 약관, 환불정책, 개인정보처리방침 세 가지를
                  찾는데, "이용권 안내"만 적혀 있으면 그 안에 환불정책이 있는 걸 모르고 지나친다 */}
              {/* button이 아니라 a로 둔다 — 심사는 사람이 아니라 크롤러가 하고, onClick만 있는
                  button은 따라갈 링크가 없어 정책 페이지를 못 찾는다. href로 발견되게 하되
                  이동은 라우터가 처리해 전체 새로고침을 막는다 */}
              <a
                className={styles.docLink}
                href="/pricing"
                onClick={(e) => { e.preventDefault(); navigate('/pricing'); }}
              >
                이용권과 환불
              </a>
              <a
                className={styles.docLink}
                href="/terms"
                onClick={(e) => { e.preventDefault(); navigate('/terms'); }}
              >
                이용약관
              </a>
              <a
                className={styles.docLink}
                href="/privacy"
                onClick={(e) => { e.preventDefault(); navigate('/privacy'); }}
              >
                개인정보처리방침
              </a>
            </div>

            {/* 전자상거래법상 초기화면 표시 의무 — 랜딩을 없앤 뒤로 이 화면이 초기화면이다 */}
            <BusinessInfo />
          </>
        )}

        {phase >= 1 && phase <= steps.length && (
          <IntakeWizard
            step={phase - 1}
            draft={draft}
            onChange={(patch) => setDraft((prev) => ({ ...prev, ...patch }))}
            onNext={() => goPhase(phase + 1)}
            onBack={goBack}
          />
        )}

        {phase === PHASE_RECAP && (
          <div className={styles.recapWrap}>
            <div className={styles.mark} />
            <h1 className={styles.hook}>
              {recapLines(draft).length ? '판이 잡혔습니다' : '이제 이야기를 들을 차례입니다'}
            </h1>
            {recapLines(draft).length > 0 && (
              <dl className={styles.recap}>
                {recapLines(draft).map(([k, v]) => (
                  <Fragment key={k}>
                    <dt>{k}</dt>
                    <dd>{v}</dd>
                  </Fragment>
                ))}
              </dl>
            )}
            <p className={styles.hookWhy}>
              {transitionHook(draft)} 이제 무슨 일이 있었는지 들려주세요.
            </p>
            <button className={styles.cta} onClick={() => goPhase(PHASE_RECAP + 1)}>
              이야기 시작하기
            </button>
            <div className={styles.recapFoot}>
              <button className={styles.skipAsk} onClick={goBack}>
                이전
              </button>
            </div>
          </div>
        )}

        {phase > PHASE_RECAP && (
          <>
            {error && <div className={styles.error}>{error}</div>}
            <div className={styles.composer}>
              <div className={styles.composerLabel}>
                이별까지의 흐름과 현재 상황을 자세히 적어주시면 더 정확하게 분석해 드립니다
              </div>
              <textarea
                className={styles.input}
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="여기에 적어주세요"
                rows={6}
                disabled={starting}
                autoFocus
              />
              <button
                className={styles.send}
                onClick={handleStart}
                disabled={!input.trim() || starting}
              >
                {starting ? '시작하는 중…' : '이야기 시작하기'}
              </button>
              <button className={styles.skipAsk} onClick={goBack} disabled={starting}>
                이전
              </button>
            </div>
          </>
        )}
      </div>
    </PhoneFrame>
  );
}
