import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { TermsContent } from '../components/TermsContent';
import { PrivacyContent } from '../components/PrivacyContent';
import { requestEmailVerification, signup, SIGNUP_CONSENTS } from '../api/auth';
import { extractErrorMessage } from '../api/client';
import styles from './LoginPage.module.css';
import { BRAND } from '../brand';

const RESEND_COOLDOWN_SECONDS = 60; // 서버 쿨다운과 동일 — UI에서 먼저 눌러볼 일이 없게

export function SignupPage() {
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [code, setCode] = useState('');
  const [codeSent, setCodeSent] = useState(false);
  const [sendingCode, setSendingCode] = useState(false);
  const [cooldown, setCooldown] = useState(0);
  const [password, setPassword] = useState('');
  const [agreeTerms, setAgreeTerms] = useState(false);
  const [agreePrivacy, setAgreePrivacy] = useState(false);
  const [agreeSensitive, setAgreeSensitive] = useState(false);
  const [agreeDisclaimer, setAgreeDisclaimer] = useState(false);
  // 오버레이로 열어 입력값이 안 날아가게 한다. terms/privacy 두 문서를 같은 시트로 돌려쓴다.
  const [showDoc, setShowDoc] = useState<'terms' | 'privacy' | null>(null);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const cooldownTimer = useRef<number | null>(null);

  useEffect(() => {
    return () => {
      if (cooldownTimer.current !== null) window.clearInterval(cooldownTimer.current);
    };
  }, []);

  function startCooldown() {
    setCooldown(RESEND_COOLDOWN_SECONDS);
    cooldownTimer.current = window.setInterval(() => {
      setCooldown((s) => {
        if (s <= 1 && cooldownTimer.current !== null) {
          window.clearInterval(cooldownTimer.current);
          cooldownTimer.current = null;
        }
        return Math.max(0, s - 1);
      });
    }, 1000);
  }

  const canRequestCode = email.trim() !== '' && cooldown === 0 && !sendingCode;

  async function handleRequestCode() {
    if (!canRequestCode) return;
    setError('');
    setSendingCode(true);
    try {
      await requestEmailVerification(email.trim());
      setCodeSent(true);
      startCooldown();
    } catch (err) {
      setError(extractErrorMessage(err, '인증 메일을 보내지 못했습니다.'));
    } finally {
      setSendingCode(false);
    }
  }

  const allAgreed = agreeTerms && agreePrivacy && agreeSensitive && agreeDisclaimer;

  function setAllAgreed(value: boolean) {
    setAgreeTerms(value);
    setAgreePrivacy(value);
    setAgreeSensitive(value);
    setAgreeDisclaimer(value);
  }

  const canSubmit =
    email.trim() !== '' &&
    /^\d{6}$/.test(code) &&
    password.length >= 8 &&
    allAgreed &&
    !submitting;

  async function handleSignup(e: React.FormEvent) {
    e.preventDefault();
    if (!canSubmit) return;
    setError('');
    setSubmitting(true);
    try {
      await signup({
        email: email.trim(),
        password,
        verificationCode: code,
        consents: [...SIGNUP_CONSENTS],
      });
      // 가입 선물(이용권)은 로그인 화면 안내로 알린다 — 받은 걸 모르면 준 게 아니다.
      navigate('/login', { state: { welcomeGift: true } });
    } catch (err) {
      setError(extractErrorMessage(err, '가입에 실패했습니다.'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <PhoneFrame>
      {/* 홈과 같은 시안 그라데이션 — 로그인 계열 화면의 톤을 맞춘다 */}
      <form className={styles.body} onSubmit={handleSignup}>
        <button type="button" className={styles.backTop} onClick={() => navigate('/login')} aria-label="뒤로">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
            <path d="M15 5l-7 7 7 7" stroke="#ebebee" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </button>
        <div className={styles.brand}>
          <div className={styles.title}>회원가입</div>
          <div className={styles.subtitle}>이메일로 {BRAND} 회원가입</div>
        </div>

        <div className={styles.fields}>
          <div className={styles.field}>
            <input
              className={styles.input}
              type="email"
              inputMode="email"
              autoComplete="email"
              placeholder="이메일"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
            <button
              type="button"
              className={styles.fieldButton}
              onClick={handleRequestCode}
              disabled={!canRequestCode}
            >
              {sendingCode ? '발송 중…' : cooldown > 0 ? `재발송 ${cooldown}초` : codeSent ? '재발송' : '인증코드 받기'}
            </button>
          </div>
          {codeSent && (
            <>
              <div className={styles.field}>
                <input
                  className={styles.input}
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  placeholder="메일로 받은 6자리 코드"
                  maxLength={6}
                  value={code}
                  onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                />
              </div>
              <div className={styles.hint}>메일이 보이지 않으면 스팸함도 확인해 주세요. 코드는 10분 동안 유효합니다.</div>
            </>
          )}
          <div className={styles.field}>
            <input
              className={styles.input}
              type="password"
              autoComplete="new-password"
              placeholder="비밀번호 (영문, 숫자 포함 8자 이상)"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>
        </div>

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
            {/* label 안의 버튼 — 기본 동작이 체크 토글로 번지지 않게 막는다 */}
            <span>
              (필수){' '}
              <button
                type="button"
                className={styles.consentLink}
                onClick={(e) => { e.preventDefault(); setShowDoc('terms'); }}
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
                onClick={(e) => { e.preventDefault(); setShowDoc('privacy'); }}
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
                onClick={(e) => { e.preventDefault(); setShowDoc('terms'); }}
              >
                면책 고지
              </button>
              를 확인했습니다
            </span>
          </label>
        </div>

        <div className={styles.error}>{error}</div>

        <button className={styles.primary} type="submit" disabled={!canSubmit}>
          {submitting ? '가입 중…' : '회원가입'}
        </button>

        {showDoc && (
          <div className={styles.sheetOverlay} onClick={() => setShowDoc(null)}>
            <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
              <div className={styles.sheetTitle}>
                {showDoc === 'terms' ? '이용약관과 면책 고지' : '개인정보처리방침'}
              </div>
              <div className={styles.sheetBody}>
                {showDoc === 'terms' ? <TermsContent /> : <PrivacyContent />}
              </div>
              <button type="button" className={styles.sheetAgree} onClick={() => setShowDoc(null)}>
                확인했습니다
              </button>
            </div>
          </div>
        )}
      </form>
    </PhoneFrame>
  );
}
