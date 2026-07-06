import { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { SwitchConfirmSheet } from '../components/SwitchConfirmSheet';
import { confirmOAuthSwitch, oauthLogin, SIGNUP_CONSENTS, type OAuthProvider } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { consumeStoredState } from '../utils/socialAuth';
import styles from './LoginPage.module.css';
import { BRAND } from '../brand';

// 카카오/네이버 인가 후 도착지. 코드를 서버로 넘겨 토큰을 받고 목록으로 보낸다.
export function OAuthCallbackPage() {
  const navigate = useNavigate();
  const { provider } = useParams();
  const [searchParams] = useSearchParams();
  const [error, setError] = useState('');
  // 게스트가 이미 가입된 소셜 계정으로 로그인한 경우 — 서버가 토큰 대신 전환 티켓을 내린다.
  const [switchTicket, setSwitchTicket] = useState<string | null>(null);
  const [switching, setSwitching] = useState(false);
  // 인가 코드는 1회용이라 StrictMode의 이펙트 이중 실행이 곧 이중 교환 실패 — ref로 한 번만 돌린다.
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    if (provider !== 'kakao' && provider !== 'naver') {
      setError('지원하지 않는 로그인 방식입니다.');
      return;
    }
    const denied = searchParams.get('error');
    if (denied) {
      setError('로그인이 취소되었습니다.');
      return;
    }
    const code = searchParams.get('code');
    const state = searchParams.get('state');
    const stored = consumeStoredState();
    if (!code) {
      setError('인가 코드를 받지 못했습니다. 다시 시도해 주세요.');
      return;
    }
    if (stored.state && state !== stored.state) {
      setError('요청을 확인하지 못했습니다. 다시 시도해 주세요.');
      return;
    }

    oauthLogin(provider as OAuthProvider, {
      code,
      state: state ?? undefined,
      redirectUri: stored.redirectUri ?? window.location.origin + window.location.pathname,
      // 인가 페이지로 넘어왔다는 것 자체가 로그인 화면의 동의 시트를 통과했다는 뜻 —
      // 신규 가입이면 서버가 이 동의를 기록하고, 기존 계정이면 무시한다.
      consents: [...SIGNUP_CONSENTS],
    })
      .then((result) => {
        // 게스트 → 기존 계정 전환은 게스트 대화를 잃는다 — 자동 이동 대신 확인을 받는다.
        if (result.switchTicket) {
          setSwitchTicket(result.switchTicket);
          return;
        }
        navigate('/stories', { replace: true });
      })
      .catch((err) => setError(extractErrorMessage(err, '소셜 로그인에 실패했습니다.')));
  }, [provider, searchParams, navigate]);

  async function handleConfirmSwitch() {
    if (!switchTicket) return;
    setSwitching(true);
    try {
      await confirmOAuthSwitch(switchTicket);
      navigate('/stories', { replace: true });
    } catch (err) {
      setSwitchTicket(null);
      setError(extractErrorMessage(err, '계정을 전환하지 못했습니다. 다시 시도해 주세요.'));
    } finally {
      setSwitching(false);
    }
  }

  return (
    <PhoneFrame>
      <div className={styles.body}>
        <div className={styles.brand}>
          <div className={styles.title}>{BRAND}</div>
          <div className={styles.subtitle}>
            {error ? '로그인에 문제가 생겼습니다.' : switchTicket ? '확인이 필요합니다.' : '로그인하는 중입니다…'}
          </div>
        </div>
        <div className={styles.spacer} />
        <div className={styles.error}>{error}</div>
        {error && (
          <button className={styles.primary} type="button" onClick={() => navigate('/login', { replace: true })}>
            로그인 화면으로
          </button>
        )}

        {switchTicket && (
          <SwitchConfirmSheet
            title="이미 가입된 계정입니다"
            message={`이 소셜 계정은 이미 ${BRAND} 회원입니다. 이 계정으로 로그인하면 지금까지 게스트로 나눈 대화는 가져올 수 없습니다.`}
            confirmLabel="게스트 대화 포기하고 로그인"
            submitting={switching}
            onConfirm={() => void handleConfirmSwitch()}
            onCancel={() => {
              // 전환 포기 — 게스트 토큰은 그대로 유효하니 하던 대화로 돌려보낸다.
              setSwitchTicket(null);
              navigate('/stories', { replace: true });
            }}
          />
        )}
      </div>
    </PhoneFrame>
  );
}
