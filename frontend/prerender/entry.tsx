import { renderToStaticMarkup } from 'react-dom/server';
import { TermsContent } from '../src/components/TermsContent';
import { PrivacyContent } from '../src/components/PrivacyContent';

// 약관과 방침을 정적 HTML로 뽑아 각 경로의 index.html에 심는다.
// 손으로 옮겨 적으면 화면과 크롤러가 보는 글이 조용히 어긋난다 — 같은 컴포넌트를 렌더한다.
export function renderDocs() {
  return {
    terms: renderToStaticMarkup(<TermsContent />),
    privacy: renderToStaticMarkup(<PrivacyContent />),
  };
}
