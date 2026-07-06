import styles from './TermsContent.module.css';
import { CONTACT_OPENCHAT_URL } from './HelpModal';
import { BRAND } from '../brand';

// 약관/면책 본문. 회원가입 오버레이와 /terms 페이지가 같은 내용을 쓰도록 하나로 모은다.
// 법적 성격의 문서라 전체 습니다 체 고정.
export function TermsContent() {
  return (
    <div className={styles.content}>
      <div className={styles.section}>면책 고지</div>
      <div className={styles.noticeBox}>
        <p className={styles.para}>
          {BRAND}의 대화와 분석 리포트는 이용자가 들려준 이야기를 근거로 만들어지는
          참고 정보입니다. 화면에 표시되는 수치는 입력한 내용을 정해진 기준으로 정리한
          결과이며, 미래를 예측하거나 실제 결과를 보장하지 않습니다.
        </p>
        <p className={styles.para}>
          AI의 특성상 부정확하거나 사실과 다른 답변이 나올 수 있습니다. 본 서비스는 의료
          행위, 심리 상담, 법률 자문이 아니며 전문가의 도움을 대체하지 않습니다.
        </p>
        <p className={styles.para}>
          서비스에서 얻은 정보를 참고하여 내린 판단과 행동, 그로 인해 발생한 결과에 대한
          책임은 이용자 본인에게 있습니다.
        </p>
      </div>

      <div className={styles.section}>이용약관</div>

      <div className={styles.clauseTitle}>제1조 (목적)</div>
      <p className={styles.para}>
        이 약관은 {BRAND}(이하 "서비스")의 이용 조건과 운영에 관한 기본적인 사항을
        정합니다.
      </p>

      <div className={styles.clauseTitle}>제2조 (서비스의 내용)</div>
      <p className={styles.para}>
        서비스는 이용자가 입력한 이별 경험을 정리하는 대화, 그 내용을 근거로 한 분석 리포트,
        대화 기록 관리 기능을 제공하는 소프트웨어입니다. 서비스가 제공하는 모든 결과물은 참고용 정보이며 그 정확성과
        완전성은 보장되지 않습니다.
      </p>

      <div className={styles.clauseTitle}>제3조 (계정)</div>
      <p className={styles.para}>
        회원가입은 이메일 인증을 거친 이메일 가입 또는 카카오, 네이버 계정 연동으로
        이루어집니다. 계정과 비밀번호를 안전하게 관리할 책임은 이용자에게 있으며, 계정을
        타인에게 양도하거나 빌려줄 수 없습니다.
      </p>

      <div className={styles.clauseTitle}>제4조 (대화 기록의 저장과 삭제)</div>
      <p className={styles.para}>
        이용자가 입력한 대화 내용, 대화에서 정리된 기억, 분석 결과는 응답 생성과 분석
        제공을 위해 서버에 저장됩니다. 이용자가 방을 삭제하면 그 방의 대화, 기억, 분석
        기록은 함께 삭제되며 복구할 수 없습니다.
      </p>

      <div className={styles.clauseTitle}>제4조의2 (평가와 후기)</div>
      <p className={styles.para}>
        이용자는 분석 결과에 평가와 후기를 남길 수 있습니다. 남긴 후기는 이용자를 알아볼 수
        없는 익명 상태로 서비스 소개와 안내 화면에 표시될 수 있습니다. 평가와 후기 작성에는
        혜택(이용권 등)이 제공될 수 있으며, 혜택이 제공된 후기를 표시할 때는 그 사실을 함께
        표기합니다. 표시된 후기를 내리고 싶으면 1:1 문의로 요청할 수 있고, 확인 후 지체 없이
        내립니다.
      </p>

      <div className={styles.clauseTitle}>제5조 (이용 한도와 이용권)</div>
      <p className={styles.para}>
        무료 이용분은 가입 또는 이벤트 시점에 이용권으로 지급되며, 지급 수량은 서비스 사정에
        따라 변경될 수 있습니다. 유료 이용권은 결제 시 안내된 횟수만큼 사용할 수 있습니다.
      </p>

      <div className={styles.clauseTitle}>제5조의2 (환불과 청약철회)</div>
      <p className={styles.para}>
        이용권은 결제 즉시 지급되는 디지털 콘텐츠입니다. 한 번도 사용하지 않은 이용권은 기간
        제한 없이 전액 환불받을 수 있으며, 환불은 1:1 문의로 접수하면 처리해 드립니다. 사용을
        시작한 이용권은 결제 시 이 내용을 안내받고 동의한 경우 전자상거래 등에서의 소비자보호에
        관한 법률 제17조 제2항에 따라 청약철회가 제한됩니다.
      </p>

      <div className={styles.clauseTitle}>제6조 (금지 행위)</div>
      <p className={styles.para}>
        타인의 개인정보를 동의 없이 입력하는 행위, 자동화된 접근이나 취약점 악용 등
        서비스의 비정상적 이용, 법령에 어긋나는 목적의 이용을 금지합니다. 위반 시 서비스
        이용이 제한될 수 있습니다.
      </p>

      <div className={styles.clauseTitle}>제7조 (서비스의 변경과 중단)</div>
      <p className={styles.para}>
        운영상 또는 기술상 필요에 따라 서비스의 전부 또는 일부가 변경되거나 중단될 수
        있습니다. 이 경우 서비스 안에서 미리 알립니다.
      </p>

      <div className={styles.clauseTitle}>제8조 (책임의 제한)</div>
      <p className={styles.para}>
        운영자는 천재지변, 통신 장애 등 불가항력으로 인한 손해, 이용자의 귀책 사유로 인한
        손해, 서비스가 제공한 참고 정보를 활용한 결과에 대해 책임을 지지 않습니다. 다만
        운영자의 고의 또는 중대한 과실로 인한 손해는 그러하지 않습니다.
      </p>

      <div className={styles.clauseTitle}>제9조 (약관의 변경)</div>
      <p className={styles.para}>
        약관이 변경되는 경우 적용일과 변경 내용을 서비스 안에서 알립니다. 변경 이후에도
        서비스를 계속 이용하면 변경된 약관에 동의한 것으로 봅니다.
      </p>

      <div className={styles.clauseTitle}>제10조 (개인정보 보호)</div>
      <p className={styles.para}>
        개인정보의 수집, 이용, 보관, 파기에 관한 사항은 개인정보처리방침에 따릅니다.
        개인정보처리방침은 로그인 화면과 서비스 안내에서 언제든지 볼 수 있습니다.
      </p>

      <div className={styles.clauseTitle}>제11조 (준거법)</div>
      <p className={styles.para}>이 약관은 대한민국 법령에 따라 해석되고 적용됩니다. 문서 버전 1.0.</p>

      <div className={styles.section}>운영자 정보</div>
      <p className={styles.para}>
        {/* 영문 상호를 병기한다 — 결제 대행사 심사자가 약관에서 법인을 확인하는데,
            한글만 있으면 계정에 등록된 영문명과 대조가 안 돼 불일치로 반려된다 */}
        상호: 케이케이티랩스 (KKTLABS) / 대표: 김도아 / 사업자등록번호: 281-20-02774 /
        통신판매업 신고: 면제 대상(직전년도 거래 50회 미만) / 연락처: 010-3449-6662 /
        전자우편: rlarlxo516@gmail.com
      </p>

      <div className={styles.section}>문의</div>
      <p className={styles.para}>
        서비스 이용, 결제, 환불 문의는 전자우편(
        <a href="mailto:rlarlxo516@gmail.com">rlarlxo516@gmail.com</a>)으로 보내주시면 확인 후
        답변드립니다. 국내 이용자는{' '}
        <a href={CONTACT_OPENCHAT_URL} target="_blank" rel="noreferrer">
          카카오 오픈채팅 1:1 문의
        </a>
        도 이용할 수 있습니다.
      </p>
    </div>
  );
}
