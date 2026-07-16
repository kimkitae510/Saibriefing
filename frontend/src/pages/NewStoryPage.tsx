import { useEffect, useRef, useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { Opening } from '../components/Opening';
import { EMPTY_DRAFT, draftToIntake, hasAnyAnswer, type IntakeDraft } from '../components/IntakeWizard';
import { tokenStore } from '../api/tokenStore';
import { createStory, listStories } from '../api/story';
import { getIntake, putIntake } from '../api/intake';
import { extractErrorMessage } from '../api/client';
import styles from './NewStoryPage.module.css';

// 이미 계정이 있는 사람이 새 이야기를 시작하는 자리. 첫 방문의 훅 화면은 건너뛰고 첫 질문부터
// 시작한다 — 무엇을 파는지는 이미 안다. 문진은 사연 단위라 방을 만들 때마다 다시 받는다.
// 상대가 다르면 기간도 연락 상태도 전부 다르다.
//
// 방은 되짚기의 시작 버튼을 누른 시점에 만든다(IntroPage와 같은 이유는 아니다 — 여기는 크롤러가 못 온다).
// 질문만 넘기다 나간 사람 뒤에 빈 방이 남지 않게 하려는 것이다. 서랍의 "새 이야기"가
// 바로 방을 만들던 때는 빈 방이 목록에 쌓였다.
export function NewStoryPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [draft, setDraft] = useState<IntakeDraft>(EMPTY_DRAFT);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const prefilled = useRef(false);

  const phase = (location.state as { openingPhase?: number } | null)?.openingPhase ?? 1;
  const goPhase = (next: number) => navigate('.', { state: { openingPhase: next } });
  // 첫 질문에서 이전은 오프닝 밖, 즉 보던 대화로 돌아가는 것이다
  const goBack = () => (phase <= 1 ? navigate('/stories') : navigate(-1));

  // 내 나이, 성별은 직전 사연에서 물려받는다 — 사연이 달라도 유저는 같은 사람이다.
  // 상대에 관한 값은 안 물려받는다.
  useEffect(() => {
    if (prefilled.current) return;
    prefilled.current = true;
    (async () => {
      try {
        const stories = await listStories();
        if (!stories.length) return;
        const previous = await getIntake(stories[0].id);
        setDraft((prev) => ({
          ...prev,
          userAge: prev.userAge || (previous.userAge == null ? '' : String(previous.userAge)),
          userGender: prev.userGender ?? previous.userGender,
        }));
      } catch {
        // 물려받기는 편의다 — 실패해도 빈 칸으로 시작하면 된다
      }
    })();
  }, []);

  if (!tokenStore.getAccess()) return <Navigate to="/" replace />;

  async function handleStart() {
    if (starting) return;
    setStarting(true);
    setError('');
    try {
      const story = await createStory();
      // 접수는 사연에 딸린 부가 정보다 — 저장이 실패해도 사연까지 막지 않는다.
      if (hasAnyAnswer(draft)) {
        await putIntake(story.id, draftToIntake(draft)).catch(() => {});
      }
      navigate(`/stories/${story.id}`, { replace: true });
    } catch (e) {
      setError(extractErrorMessage(e, '시작하지 못했습니다. 잠시 후 다시 시도해 주세요.'));
      setStarting(false);
    }
  }

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <Opening
          phase={phase}
          goPhase={goPhase}
          goBack={goBack}
          draft={draft}
          onDraft={(patch) => setDraft((prev) => ({ ...prev, ...patch }))}
          starting={starting}
          error={error}
          onStart={handleStart}
        />
      </div>
    </PhoneFrame>
  );
}
