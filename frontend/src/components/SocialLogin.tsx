import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { SwitchConfirmSheet } from './SwitchConfirmSheet';
import {
  confirmOAuthSwitch,
  oauthLogin,
  SIGNUP_CONSENTS,
  type OAuthProvider,
} from '../api/auth';
import { extractErrorMessage } from '../api/client';
import { redirectUriFor, startSocialLogin } from '../utils/socialAuth';
import naverIconUrl from '../assets/naver-login.png';
import styles from './SocialLogin.module.css';
import { BRAND } from '../brand';

// 소셜은 첫 로그인이 곧 가입이라 인가로 넘어가기 전에 동의를 받는다.
// 이 기기에서 한 번 동의했으면 다음부터 시트를 생략한다(서버는 신규 가입일 때만 검사).
const SOCIAL_CONSENT_KEY = 'social-consent-v1';

// 진입점이 두 곳(첫 화면 하단, 로그인 화면)인데 동의 시트를 한쪽만 달면 동의 없이 가입되는
// 문이 생긴다. 버튼과 시트를 한 컴포넌트가 같이 들고 다니게 해서 그 구멍을 막는다.
// wide는 이름을 단 가로 버튼, icon은 원형 심볼만.
export function SocialLogin({
  variant,
  onError,
}: {
  variant: 'wide' | 'icon';
  onError: (message: string) => void;
}) {
  const navigate = useNavigate();
  // 동의 시트가 열려 있으면 어느 소셜로 이어갈지 기억한다
  const [consentFor, setConsentFor] = useState<OAuthProvider | null>(null);
  // 게스트가 이미 가입된 소셜 계정으로 로그인 — 서버가 내린 티켓으로 확인을 거친다
  const [switchTicket, setSwitchTicket] = useState<string | null>(null);
  const [switching, setSwitching] = useState(false);
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [agreePrivacy, setAgreePrivacy] = useState(false);
  const [agreeSensitive, setAgreeSensitive] = useState(false);
  const [agreeDisclaimer, setAgreeDisclaimer] = useState(false);
  const allAgreed = agreeTerms && agreePrivacy && agreeSensitive && agreeDisclaimer;

  function handleSocial(provider: OAuthProvider) {
    onError('');
    if (!localStorage.getItem(SOCIAL_CONSENT_KEY)) {
      setConsentFor(provider);
      return;
    }
    void proceedSocial(provider);
  }

  async function proceedSocial(provider: OAuthProvider) {
    if (startSocialLogin(provider) === 'redirected') return;
    // 키 미설정(개발) — 백엔드 mock 프로바이더로 바로 교환한다. 같은 code라 항상 같은 개발 계정.
    try {
      const result = await oauthLogin(provider, {
        code: `dev-${provider}`,
        redirectUri: redirectUriFor(provider),
        consents: [...SIGNUP_CONSENTS],
      });
      if (result.switchTicket) {
        setSwitchTicket(result.switchTicket);
        return;
      }
      navigate('/stories');
    } catch (err) {
      onError(extractErrorMessage(err, '소셜 로그인에 실패했습니다.'));
    }
  }

  async function handleConfirmSwitch() {
    if (!switchTicket) return;
    setSwitching(true);
    try {
      await confirmOAuthSwitch(switchTicket);
      navigate('/stories');
    } catch (err) {
      setSwitchTicket(null);
      onError(extractErrorMessage(err, '계정을 전환하지 못했습니다. 다시 시도해 주세요.'));
    } finally {
      setSwitching(false);
    }
  }

  function agreeAndStart() {
    if (!allAgreed || !consentFor) return;
    localStorage.setItem(SOCIAL_CONSENT_KEY, '1');
    const provider = consentFor;
    setConsentFor(null);
    void proceedSocial(provider);
  }

  // 심볼 색은 가이드 규격(#000000)에 맞춘다. 형태와 비율은 변경 불가 항목이라
  // 콘솔 [도구] > [리소스 다운로드]의 공식 자산으로 교체해야 한다.
  //
  // 크기는 CSS가 정한다 — 네이버 공식 자산의 N이 원 지름의 35.7%라, 옆에 나란히 서는
  // 말풍선도 같은 비율이어야 한쪽만 작아 보이지 않는다. 높이는 auto로 둬서 비율을 건드리지 않는다.
  const kakaoSymbol = (className: string) => (
    <svg className={className} viewBox="0 0 24 22" aria-hidden="true">
      <path
        d="M12 1 C5.9 1 1 4.9 1 9.7 c0 3.1 2 5.8 5.1 7.3 L5 21 l4.7-3 c.7 .1 1.5 .2 2.3 .2 6.1 0 11-3.9 11-8.5 C23 4.9 18.1 1 12 1 Z"
        fill="#000000"
      />
    </svg>
  );

  return (
    <>
      {variant === 'wide' ? (
        // 원색 판 두 장이 나란히 서면 화면을 브랜드가 먹는다. 계정 연동 목록처럼
        // 판은 배경 결에 맞춰 중립으로 두고, 브랜드 색은 왼쪽 동그란 심볼만 갖는다
        <div className={styles.wideRow}>
          <button className={styles.wideBtn} type="button" onClick={() => handleSocial('kakao')}>
            <span className={`${styles.badge} ${styles.badgeKakao}`}>
              {kakaoSymbol(styles.badgeSymbol)}
            </span>
            카카오로 계속하기
          </button>
          <button className={styles.wideBtn} type="button" onClick={() => handleSocial('naver')}>
            <img className={styles.badgeImg} src={naverIconUrl} alt="" aria-hidden="true" />
            네이버로 계속하기
          </button>
        </div>
      ) : (
        // 첫 화면 하단의 원형 심볼 — 관습상 이 자리는 SNS 공유 버튼 자리라
        // 라벨이 없으면 공유하기로 읽힌다. 한 줄을 위에 세워 오독을 막는다
        <div className={styles.iconWrap}>
          <div className={styles.iconLabel}>이미 계정이 있으신가요?</div>
          <div className={styles.iconRow}>
            <button
              className={`${styles.iconBtn} ${styles.iconKakao}`}
              type="button"
              onClick={() => handleSocial('kakao')}
              aria-label="카카오로 로그인"
            >
              {kakaoSymbol(styles.iconSymbol)}
            </button>
            <button
              className={styles.iconBtn}
              type="button"
              onClick={() => handleSocial('naver')}
              aria-label="네이버로 로그인"
            >
              <img className={styles.iconImg} src={naverIconUrl} alt="" aria-hidden="true" />
            </button>
          </div>
        </div>
      )}

      {consentFor && (
        <div className={styles.sheetOverlay} onClick={() => setConsentFor(null)}>
          <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
            <div className={styles.sheetTitle}>시작하기 전에 확인해 주세요</div>
            <div className={styles.consentBox}>
              <label className={`${styles.consentRow} ${styles.consentAll}`}>
                <input
                  type="checkbox"
                  className={styles.consentCheck}
                  checked={allAgreed}
                  onChange={(e) => {
                    setAgreeTerms(e.target.checked);
                    setAgreePrivacy(e.target.checked);
                    setAgreeSensitive(e.target.checked);
                    setAgreeDisclaimer(e.target.checked);
                  }}
                />
                <span>모두 동의합니다</span>
              </label>
              <label className={styles.consentRow}>
                <input
                  type="checkbox"
                  className={styles.consentCheck}
                  checked={agreeTerms}
                  onChange={(e) => setAgreeTerms(e.target.checked)}
                />
                <span>
                  (필수){' '}
                  <button
                    type="button"
                    className={styles.consentLink}
                    onClick={(e) => { e.preventDefault(); navigate('/terms'); }}
                  >
                    이용약관
                  </button>
                  에 동의합니다
                </span>
              </label>
              <label className={styles.consentRow}>
                <input
                  type="checkbox"
                  className={styles.consentCheck}
                  checked={agreePrivacy}
                  onChange={(e) => setAgreePrivacy(e.target.checked)}
                />
                <span>
                  (필수){' '}
                  <button
                    type="button"
                    className={styles.consentLink}
                    onClick={(e) => { e.preventDefault(); navigate('/privacy'); }}
                  >
                    개인정보 수집, 이용
                  </button>
                  에 동의합니다
                </span>
              </label>
              <label className={styles.consentRow}>
                <input
                  type="checkbox"
                  className={styles.consentCheck}
                  checked={agreeSensitive}
                  onChange={(e) => setAgreeSensitive(e.target.checked)}
                />
                <span>(필수) 이별, 연애 이야기(민감할 수 있는 정보) 수집, 이용에 동의합니다</span>
              </label>
              <label className={styles.consentRow}>
                <input
                  type="checkbox"
                  className={styles.consentCheck}
                  checked={agreeDisclaimer}
                  onChange={(e) => setAgreeDisclaimer(e.target.checked)}
                />
                <span>
                  (필수) AI 답변은 참고 정보라는{' '}
                  <button
                    type="button"
                    className={styles.consentLink}
                    onClick={(e) => { e.preventDefault(); navigate('/terms'); }}
                  >
                    면책 고지
                  </button>
                  를 확인했습니다
                </span>
              </label>
            </div>
            <button
              type="button"
              className={styles.sheetAgree}
              disabled={!allAgreed}
              onClick={agreeAndStart}
            >
              동의하고 시작하기
            </button>
          </div>
        </div>
      )}

      {switchTicket && (
        <SwitchConfirmSheet
          title="이미 가입된 계정입니다"
          message={`이 소셜 계정은 이미 ${BRAND} 회원입니다. 이 계정으로 로그인하면 지금까지 게스트로 나눈 대화는 가져올 수 없습니다.`}
          confirmLabel="게스트 대화 포기하고 로그인"
          submitting={switching}
          onConfirm={() => void handleConfirmSwitch()}
          onCancel={() => setSwitchTicket(null)}
        />
      )}
    </>
  );
}
