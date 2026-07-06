import styles from './TermsContent.module.css';
import { CONTACT_OPENCHAT_URL } from './HelpModal';
import { BRAND } from '../brand';

// 개인정보처리방침 본문. 가입 동의 오버레이와 /privacy 페이지가 같은 내용을 쓰도록 하나로 모은다.
// 문서 스타일은 약관과 동일해야 해서 TermsContent.module.css를 그대로 쓴다.
// 법적 성격의 문서라 전체 습니다 체 고정. 개정 시 버전을 올리고 서버 ConsentService.DOC_VERSION과 맞춘다.
export function PrivacyContent() {
  return (
    <div className={styles.content}>
      <div className={styles.section}>개인정보처리방침</div>
      <p className={styles.para}>
        {BRAND}(이하 "서비스")는 이용자의 개인정보를 소중히 다루며, 개인정보 보호법 등 관계
        법령을 지킵니다. 이 방침은 서비스가 어떤 정보를 왜 모으고, 어떻게 쓰고 지키며, 언제
        지우는지를 설명합니다.
      </p>

      <div className={styles.clauseTitle}>제1조 (수집하는 개인정보와 수집 방법)</div>
      <p className={styles.para}>
        회원가입 시: 이메일 가입은 이메일 주소와 비밀번호(암호화 저장)를, 카카오/네이버 가입은
        소셜 계정 식별자와 이메일 주소(제공에 동의한 경우)를 수집합니다.
      </p>
      <p className={styles.para}>
        서비스 이용 시: 이용자가 입력한 대화 내용과 사연, 대화에서 정리된 기억, 분석
        결과가 저장됩니다. 유료 결제 시 주문과 결제 기록(상품, 금액, 결제 수단, 승인 시각)이
        저장됩니다. 카드 번호 등 결제 수단 정보는 결제대행사(Paddle.com Market Ltd)가 처리하며
        서비스는 보관하지 않습니다.
      </p>
      <p className={styles.para}>
        자동 수집: 서비스 안정 운영을 위한 접속 기록과 기기 정보(IP 주소 등)가 남을 수
        있습니다.
      </p>

      <div className={styles.clauseTitle}>제2조 (민감할 수 있는 정보의 처리)</div>
      <p className={styles.para}>
        이별과 연애에 관한 이야기의 특성상, 이용자가 들려주는 내용에 연애 경험, 심리 상태 등
        민감할 수 있는 정보가 포함될 수 있습니다. 이 정보는 가입 시 별도 동의를 받아 대화와
        분석 제공 목적으로 처리합니다. 개인을 알아볼 수 없게 처리해 활용하는 경우(제4조의2)를
        제외하면 다른 목적으로 쓰지 않으며, 이용자를 알아볼 수 있는 형태로 다른 이용자나
        제3자에게 공개하지 않습니다.
      </p>

      <div className={styles.clauseTitle}>제3조 (개인정보의 국외 이전)</div>
      <p className={styles.para}>
        응답과 분석을 만들기 위해 대화 내용이 Google에 전송되고, 서비스 운영을 위해 이용자의
        기록이 국외 클라우드 서버에 보관됩니다. 개인정보 보호법 제28조의8에 따라 다음 내용을
        알립니다.
      </p>
      <div className={styles.noticeBox}>
        <p className={styles.para}>이전받는 자: Google LLC (Google Cloud, Vertex AI)</p>
        <p className={styles.para}>이전되는 국가: 미국 등 Google 데이터센터 소재국</p>
        <p className={styles.para}>
          이전 항목: 대화 메시지와 사연 내용. 이메일 등 계정 정보는 전송하지 않습니다.
        </p>
        <p className={styles.para}>이전 시기와 방법: 응답을 만들 때마다 암호화된 통신으로 전송</p>
        <p className={styles.para}>이전 목적: 대화 응답과 분석 리포트 생성</p>
        <p className={styles.para}>
          보유와 이용 기간: 응답 생성 처리에 필요한 동안만. Google은 유료 API 정책에 따라
          전송된 내용을 AI 모델 학습에 사용하지 않습니다.
        </p>
      </div>
      {/* Google은 응답 생성을 위한 전송이고 이쪽은 보관이다 — 성격이 달라 항목을 나눠 적는다 */}
      <div className={styles.noticeBox}>
        <p className={styles.para}>이전받는 자: Oracle Corporation (Oracle Cloud Infrastructure)</p>
        <p className={styles.para}>이전되는 국가: 일본</p>
        <p className={styles.para}>
          이전 항목: 계정 정보, 대화 메시지와 사연 내용, 분석 기록, 결제 기록
        </p>
        <p className={styles.para}>
          이전 시기와 방법: 서비스를 이용하는 동안 암호화된 통신으로 전송되어 서버에 저장
        </p>
        <p className={styles.para}>이전 목적: 서비스 운영에 필요한 데이터 보관</p>
        <p className={styles.para}>
          보유와 이용 기간: 이 방침에 정한 보유 기간과 같습니다. Oracle은 보관 설비만 제공하며
          저장된 내용을 열람하거나 다른 목적으로 이용하지 않습니다.
        </p>
      </div>
      <p className={styles.para}>
        두 전송 모두 서비스 제공에 꼭 필요해서, 원하지 않는 경우 서비스를 이용하지 않는 방법으로
        거부할 수 있습니다.
      </p>

      <div className={styles.clauseTitle}>제4조 (처리 위탁)</div>
      <p className={styles.para}>
        결제 처리와 환불은 Paddle.com Market Ltd에, 응답 생성은 Google LLC에, 데이터 보관은 Oracle
        Corporation에 위탁합니다. 수탁자가 바뀌면 이 방침을 고쳐 알립니다.
      </p>

      <div className={styles.clauseTitle}>제4조의2 (익명 처리 정보의 활용과 품질 관리 열람)</div>
      <p className={styles.para}>
        서비스는 대화와 사연에서 정리한 정보를 개인을 알아볼 수 없는 형태(이름, 연락처 등
        식별 요소를 제거하고 상황 분류와 통계 수치로 가공)로 바꾸어 분석 품질의 개선과 검증,
        비슷한 상황의 익명 사례 제공, 통계 작성과 서비스 소개 콘텐츠에 활용할 수 있습니다.
        익명 처리된 정보는 특정 개인을 다시 알아볼 수 있는 형태로 되돌리지 않습니다.
      </p>
      <p className={styles.para}>
        또한 분석 오류 확인과 품질 관리를 위해 운영자가 필요한 최소 범위에서 대화 내용을
        열람할 수 있습니다. 열람은 해당 목적에 한정되며, 열람한 내용을 이 방침에 정한 목적
        밖으로 이용하거나 공개하지 않습니다.
      </p>

      <div className={styles.clauseTitle}>제5조 (보유 기간과 파기)</div>
      <p className={styles.para}>
        이용자가 대화방을 삭제하면 그 방의 대화, 기억, 분석 기록은 즉시 삭제되며 복구할 수
        없습니다. 회원 탈퇴 시 개인정보는 지체 없이 파기합니다. 다만 다음은 예외로 보관 후
        파기합니다.
      </p>
      <div className={styles.noticeBox}>
        <p className={styles.para}>
          부정 가입과 부정 이용 방지를 위한 최소 정보(가입 이메일, 소셜 계정 식별자): 탈퇴 후
          1년
        </p>
        <p className={styles.para}>
          전자상거래법에 따른 보존: 계약과 청약철회 기록 5년, 대금결제와 재화공급 기록 5년,
          소비자 불만과 분쟁처리 기록 3년
        </p>
        <p className={styles.para}>통신비밀보호법에 따른 접속 기록: 3개월</p>
      </div>

      <div className={styles.clauseTitle}>제6조 (이용자의 권리)</div>
      <p className={styles.para}>
        이용자는 언제든지 자신의 개인정보를 열람, 정정하거나 삭제와 처리 정지를 요구할 수
        있습니다. 대화방 삭제와 회원 탈퇴는 서비스 안에서 직접 할 수 있고, 그 밖의 요청은
        아래 문의 창구로 접수하면 지체 없이 처리합니다.
      </p>

      <div className={styles.clauseTitle}>제7조 (안전성 확보 조치)</div>
      <p className={styles.para}>
        비밀번호는 복호화할 수 없는 방식으로 암호화해 저장하고, 모든 통신 구간은 암호화하며,
        개인정보에 접근할 수 있는 권한을 최소한으로 관리합니다.
      </p>

      <div className={styles.clauseTitle}>제8조 (개인정보 보호책임자와 문의)</div>
      <p className={styles.para}>
        {/* 법(30조 1항 6호)은 성명 또는 담당 부서와 연락처 중 택일 — 운영자 실명 노출을 피해 담당 창구로 표기 */}
        개인정보 보호 업무는 서비스 운영자가 직접 담당합니다. 개인정보에 관한 문의, 불만, 피해
        구제 요청은 전자우편(<a href="mailto:rlarlxo516@gmail.com">rlarlxo516@gmail.com</a>)으로
        보내주시면 확인 후 답변드립니다. 국내 이용자는{' '}
        <a href={CONTACT_OPENCHAT_URL} target="_blank" rel="noreferrer">
          카카오 오픈채팅 1:1 문의
        </a>
        도 이용할 수 있습니다.
      </p>

      <div className={styles.clauseTitle}>제9조 (방침의 변경)</div>
      <p className={styles.para}>
        이 방침이 바뀌면 적용일과 변경 내용을 서비스 안에서 미리 알립니다. 문서 버전 1.0.
      </p>
    </div>
  );
}
