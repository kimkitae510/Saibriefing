import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import {
  confirmOAuthSwitch,
  oauthLogin,
  SIGNUP_CONSENTS,
  type OAuthProvider,
} from '../api/auth';
import { SwitchConfirmSheet } from '../components/SwitchConfirmSheet';
import { extractErrorMessage } from '../api/client';
import { redirectUriFor, startSocialLogin } from '../utils/socialAuth';
import styles from './LoginPage.module.css';
import { BRAND } from '../brand';

// 로그인 화면과 같은 키 — 이 기기에서 한 번 동의했으면 다시 묻지 않는다.
const SOCIAL_CONSENT_KEY = 'social-consent-v1';

// 게스트 → 계정 연결. 로그인 상태(토큰)로 그대로 인가를 태우면 서버가 게스트 행을 승격하므로
// 새 계정이 생기지 않고 지금까지의 대화가 이어진다.
//
// 이메일 경로는 화면에서 내렸다 — 연결은 연결 선물(분석 1회)을 주는 지점인데,
// 지메일이 점과 +태그를 무시해 한 편지함으로 주소를 무한히 변형할 수 있어 인증 코드로도
// 어뷰징을 못 막는다(로그인 화면에서 이메일 진입을 내린 것과 같은 이유). 카카오, 네이버는
// 뒤에 본인인증이 걸려 있어 계정을 새로 파는 비용이 훨씬 크다.
// linkGuestEmail API와 가입 흐름은 그대로 살아 있다 — 주소 정규화를 붙이면 되살릴 수 있다.
export function GuestLinkPage() {
  const navigate = useNavigate();
  const [error, setError] = useState('');
  // 동의 시트가 열려 있으면 어느 소셜로 이어갈지 기억한다
  const [consentFor, setConsentFor] = useState<OAuthProvider | null>(null);
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [agreePrivacy, setAgreePrivacy] = useState(false);
  const [agreeSensitive, setAgreeSensitive] = useState(false);
  const [agreeDisclaimer, setAgreeDisclaimer] = useState(false);
  // 그 소셜로 가입된 계정이 이미 있을 때 서버가 내리는 전환 티켓 — 확인 시트를 띄우는 근거
  const [switchTicket, setSwitchTicket] = useState<string | null>(null);
  const [switching, setSwitching] = useState(false);

  const allAgreed = agreeTerms && agreePrivacy && agreeSensitive && agreeDisclaimer;

  function setAllAgreed(value: boolean) {
    setAgreeTerms(value);
    setAgreePrivacy(value);
    setAgreeSensitive(value);
    setAgreeDisclaimer(value);
  }

  // 연결도 실질적 가입(선물 지급, 동의 이력 기록)이라 인가로 넘어가기 전에 동의를 받는다.
  function handleSocial(provider: OAuthProvider) {
    setError('');
    if (!localStorage.getItem(SOCIAL_CONSENT_KEY)) {
      setConsentFor(provider);
      return;
    }
    void proceedSocial(provider);
  }

  function agreeAndStart() {
    if (!allAgreed || !consentFor) return;
    localStorage.setItem(SOCIAL_CONSENT_KEY, '1');
    const provider = consentFor;
    setConsentFor(null);
    void proceedSocial(provider);
  }

  async function proceedSocial(provider: OAuthProvider) {
    // 인가 페이지로 리다이렉트되면 토큰이 localStorage에 남아 콜백에서 그대로 승격된다.
    if (startSocialLogin(provider) === 'redirected') return;
    // 키 미설정(개발) — 토큰을 지닌 채 mock 교환하면 서버가 게스트를 승격한다.
    try {
      const result = await oauthLogin(provider, {
        code: `dev-${provider}`,
        redirectUri: redirectUriFor(provider),
        consents: [...SIGNUP_CONSENTS],
      });
      // 그 소셜로 가입된 계정이 이미 있음 — 승격이 아니라 전환(게스트 대화 유실)이라 확인을 거친다
      if (result.switchTicket) {
        setSwitchTicket(result.switchTicket);
        return;
      }
      navigate('/stories');
    } catch (err) {
      setError(extractErrorMessage(err, '계정을 연결하지 못했습니다.'));
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
      setError(extractErrorMessage(err, '계정을 전환하지 못했습니다. 다시 시도해 주세요.'));
    } finally {
      setSwitching(false);
    }
  }

  return (
    <PhoneFrame>
      <div className={`${styles.body} ${styles.linkBody}`}>
        <button type="button" className={styles.backTop} onClick={() => navigate(-1)} aria-label="뒤로">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M15 5l-7 7 7 7" stroke="#ebebee" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>

        <div className={styles.linkHero}>
          <div className={styles.title}>계정 연결하기</div>
          <div className={styles.subtitle}>지금까지의 대화를 그대로 이어가요</div>
          <div className={styles.linkGift}>연결하면 분석 1회가 열려요</div>
        </div>

        <div className={`${styles.socialRow} ${styles.linkSocial}`}>
          <button
            className={`${styles.circleBtn} ${styles.circleKakao}`}
            type="button"
            aria-label="카카오로 연결하기"
            onClick={() => handleSocial('kakao')}
          >
            <svg width="23" height="21" viewBox="0 0 24 22" aria-hidden="true">
              <path
                d="M12 1 C5.9 1 1 4.9 1 9.7 c0 3.1 2 5.8 5.1 7.3 L5 21 l4.7-3 c.7 .1 1.5 .2 2.3 .2 6.1 0 11-3.9 11-8.5 C23 4.9 18.1 1 12 1 Z"
                fill="#191600"
              />
            </svg>
          </button>
          <button
            className={`${styles.circleBtn} ${styles.circleNaver}`}
            type="button"
            aria-label="네이버로 연결하기"
            onClick={() => handleSocial('naver')}
          >
            <svg width="16" height="16" viewBox="0 0 18 18" aria-hidden="true">
              <path d="M2 1 h4.6 l4.7 7 V1 H16 v16 h-4.6 L6.7 10 v7 H2 Z" fill="#fff" />
            </svg>
          </button>
        </div>

        {/* 랜딩이 토큰을 든 사람을 대화로 바로 보내므로, 게스트가 된 뒤엔 랜딩의 소셜 로그인에
            다시 닿을 수 없다. 이미 회원인데 게스트로 시작해버린 사람에게는 이 화면이 자기
            계정으로 돌아가는 유일한 문인데, 제목이 "계정 연결하기"라 자기 문인 줄 모른다 */}
        <div className={styles.linkBack}>
          이미 {BRAND} 계정이 있으시다면 그 소셜을 눌러 주세요. 연결 대신 원래 계정으로 로그인됩니다.
        </div>

        <div className={`${styles.error} ${styles.landError}`}>{error}</div>

        {consentFor && (
          <div className={styles.sheetOverlay} onClick={() => setConsentFor(null)}>
            <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
              <div className={styles.sheetTitle}>연결하기 전에 확인해 주세요</div>
              <div className={styles.consentBox}>
                <label className={`${styles.consentRow} ${styles.consentAll}`}>
                  <input
                    type="checkbox"
                    className={styles.consentCheck}
                    checked={allAgreed}
                    onChange={(e) => setAllAgreed(e.target.checked)}
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
                동의하고 연결하기
              </button>
            </div>
          </div>
        )}

        {switchTicket && (
          <SwitchConfirmSheet
            title="이미 가입된 계정입니다"
            message={`이 소셜 계정은 이미 ${BRAND} 회원입니다. 연결이 아니라 그 계정으로 로그인하게 되며, 지금까지 게스트로 나눈 대화는 가져올 수 없습니다.`}
            confirmLabel="게스트 대화 포기하고 로그인"
            submitting={switching}
            onConfirm={() => void handleConfirmSwitch()}
            onCancel={() => setSwitchTicket(null)}
          />
        )}
      </div>
    </PhoneFrame>
  );
}
