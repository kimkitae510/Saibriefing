import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { BusinessInfo } from '../components/BusinessInfo';
import { getPaymentConfig, type PaymentItemView } from '../api/payment';
import styles from './PricingPage.module.css';
import { BRAND_EN } from '../brand';

const KIND_LABEL: Record<string, string> = { CHAT: '대화', ASSESSMENT: '분석', MATCH: '비슷한 사례' };

// 로그인 없이 무엇을 얼마에 파는지 보여주는 공개 페이지.
// 결제 대행사 심사자는 계정을 만들지 않고 사이트를 보기 때문에, 가격이 결제 화면(로그인 뒤)에만
// 있으면 확인할 방법이 없다. 첫 화면에 가격을 박는 대신 이 페이지를 따로 두는 이유는,
// 이별 직후에 들어온 사람에게 인사보다 가격표를 먼저 보이고 싶지 않아서다.
export function PricingPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<PaymentItemView[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    getPaymentConfig()
      .then((config) => alive && setItems(config.items))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.topbar}>
          <button className={styles.backButton} onClick={() => navigate(-1)} aria-label="뒤로">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 5l-7 7 7 7" stroke="#ebebee" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className={styles.topTitle}>이용권과 환불 정책</div>
        </div>

        <div className={styles.body}>
          <p className={styles.lead}>
            {/* 브랜드 뒤에 조사를 붙이지 않는다 — 이름의 받침에 따라 은/는이 갈려서
                이름을 바꿀 때마다 문장이 깨진다 */}
            이별을 정리하는 분석 도구입니다. 이별까지의 상황을 입력하면 소프트웨어가 유형과
            요인을 판정해 분석 리포트를 만들고, 기록을 계정에 보관합니다. 상담사가 응대하지
            않으며 모든 판정과 문장은 소프트웨어가 자동으로 만듭니다.
          </p>

          {failed && <div className={styles.state}>가격 정보를 불러오지 못했습니다.</div>}
          {!failed && items === null && <div className={styles.state}>불러오는 중…</div>}

          {items?.map((item) => (
            <div className={styles.card} key={item.code}>
              <div className={styles.itemName}>{item.name}</div>
              <div className={styles.price}>{item.amount.toLocaleString()}원</div>
              <ul className={styles.grantList}>
                {/* 비슷한 사례는 리포트 안에 들어가는 내용이라 따로 세지 않는다 —
                    목록에 올리면 두 가지를 파는 것처럼 읽힌다 */}
                {item.grants
                  .filter((grant) => grant.kind === 'ASSESSMENT')
                  .map((grant) => (
                    <li key={grant.kind}>
                      {KIND_LABEL[grant.kind] ?? grant.kind} {grant.count}회
                    </li>
                  ))}
                <li>사용 기한 없음</li>
                <li>결제 즉시 계정에 지급</li>
              </ul>
            </div>
          ))}

          {/* 결제 대행사 요건에 "핵심 기능 또는 제공물"이 있다. 리포트가 로그인 뒤에만
              보여서 무엇을 파는지 확인할 방법이 없었다 — 여기서 구성으로 밝힌다 */}
          <div className={styles.sectionTitle}>기능</div>
          <ul className={styles.grantList}>
            <li>사연별 대화방 — 사연마다 방을 따로 두고 기록을 보관합니다</li>
            <li>분석 리포트 생성 — 대화와 입력한 사실을 읽고 판정합니다</li>
            <li>재분석 — 새로운 사실을 넣으면 다시 계산합니다</li>
            <li>변화 추이 — 분석 기록이 쌓이면 등급 변화를 볼 수 있습니다</li>
            <li>비슷한 사례 비교 — 같은 구도의 익명 사례를 찾아 보여줍니다</li>
            <li>공유 링크 — 결과를 읽기 전용 링크로 넘길 수 있습니다</li>
          </ul>

          <div className={styles.sectionTitle}>분석 리포트에 담기는 내용</div>
          <ul className={styles.grantList}>
            <li>이별의 유형 판정과 그렇게 본 근거</li>
            <li>유리하게, 불리하게 작용한 요인과 각각의 근거 문장</li>
            <li>재회 가능성 등급 (매우 낮음, 낮음, 보통, 높음, 매우 높음)</li>
            <li>비슷한 상황의 익명 사례</li>
            <li>앞으로 무엇을 하면 좋을지에 대한 정리</li>
          </ul>

          <div className={styles.sectionTitle}>상품 형태</div>
          <p className={styles.para}>
            <strong>실물 상품이 없습니다.</strong> 배송되는 물건이 없고, 우편이나 오프라인으로
            전달되는 것도 없습니다. 결제하면 브라우저 화면에서 바로 분석 리포트를 보시고,
            기록은 계정에 남아 언제든 다시 열 수 있습니다. 상담사가 응대하지 않으며 모든 결과는
            소프트웨어가 자동으로 만듭니다.
          </p>

          {/* 결제 대행사 심사자는 영어권이라 이 화면을 기계번역으로 본다. 그 과정에서
              "이용권"이 교환권으로, "리포트"가 인쇄물로 읽혀 실물 상품으로 오해받았다(실측).
              번역을 거치지 않고 읽히도록 영문 요약을 같은 자리에 둔다 */}
          <p className={styles.para} lang="en">
            {BRAND_EN} is a web application. Customers buy access to the software, which
            analyses the text they enter and produces a written report shown in the browser.
            There is no physical product, nothing is shipped, and nothing is delivered offline.
            No human counsellor, coach or advisor is involved.
          </p>

          <div className={styles.sectionTitle}>환불 정책</div>
          <p className={styles.para}>
            이용권은 결제 즉시 지급되는 디지털 콘텐츠입니다. 한 번도 사용하지 않은 이용권은 기간
            제한 없이 전액 환불받을 수 있으며, 1:1 문의로 접수하면 처리해 드립니다. 사용을 시작한
            이용권은 결제 시 이 내용을 안내받고 동의한 경우 전자상거래 등에서의 소비자보호에 관한
            법률 제17조 제2항에 따라 청약철회가 제한됩니다.
          </p>

          <div className={styles.sectionTitle}>문의</div>
          <p className={styles.para}>
            결제와 환불 문의는 아래 사업자정보의 전자우편주소로 연락해 주세요.
          </p>

          <div className={styles.docLinks}>
            {/* IntroPage와 같은 이유로 a — 크롤러가 따라갈 href가 있어야 한다 */}
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

          <BusinessInfo defaultOpen />
        </div>
      </div>
    </PhoneFrame>
  );
}
