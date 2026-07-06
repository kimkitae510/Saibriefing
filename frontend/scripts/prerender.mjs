import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

// SPA라 모든 경로가 같은 index.html을 받는다. 결제 대행사 심사기는 자바스크립트를 돌리지 않고
// HTML만 가져가므로, 그대로 두면 /terms, /privacy, /pricing이 메인과 바이트 단위로 똑같아
// "정책 페이지를 줬는데 열어보니 다 같은 페이지"가 된다 — 없는 것과 같다.
//
// 그래서 경로마다 index.html을 따로 만들어 그 경로의 내용을 <noscript>에 심는다.
// nginx의 try_files $uri $uri/ /index.html 이 dist/terms/index.html 을 먼저 집는다.
// 약관과 방침 본문은 화면과 같은 컴포넌트를 렌더해서 넣는다(따로 적으면 조용히 어긋난다).
// 브랜드는 빌드 때 넘긴 값을 그대로 쓴다(vite와 같은 값이라야 화면과 메타가 안 어긋난다)
const BRAND = process.env.VITE_BRAND || '사이브리핑';
const BRAND_EN = process.env.VITE_BRAND_EN || 'Saibriefing';

const here = dirname(fileURLToPath(import.meta.url));
const dist = resolve(here, '../dist');

// 윈도우에서는 절대 경로를 그대로 import 못 한다(c: 를 프로토콜로 읽는다) — file:// URL로 바꾼다
const { renderDocs } = await import(pathToFileURL(resolve(here, '../dist-ssr/entry.js')).href);
const docs = renderDocs();

// 가격과 환불 정책은 화면이 서버 API로 그려서 렌더할 컴포넌트가 없다 — 여기 직접 적는다.
// 금액을 바꾸면 PaymentItem.java(원본)와 이 문구를 같이 고친다.
const PRICING = `
  <h1>이용권과 환불 정책</h1>
  <p>
    이별을 정리하는 분석 도구입니다. 이별까지의 상황을 입력하면 소프트웨어가 유형과 요인을
    판정해 분석 리포트를 만들고, 기록을 계정에 보관합니다. 상담사가 응대하지 않으며 모든
    판정과 문장은 소프트웨어가 자동으로 만듭니다.
  </p>
  <h2>기능</h2>
  <ul>
    <li>사연별 대화방과 기록 보관</li>
    <li>분석 리포트 생성</li>
    <li>새로운 사실을 넣고 다시 분석</li>
    <li>분석 기록의 등급 변화 추이</li>
    <li>비슷한 상황의 익명 사례 비교</li>
    <li>결과 공유 링크</li>
  </ul>
  <h2>분석 리포트에 담기는 내용</h2>
  <ul>
    <li>이별의 유형 판정과 그렇게 본 근거</li>
    <li>유리하게, 불리하게 작용한 요인과 각각의 근거 문장</li>
    <li>재회 가능성 등급 (매우 낮음, 낮음, 보통, 높음, 매우 높음)</li>
    <li>비슷한 상황의 익명 사례</li>
    <li>앞으로 무엇을 하면 좋을지에 대한 정리</li>
  </ul>
  <h2>이용권</h2>
  <p>
    분석 리포트 1건 6,900원. 결제 즉시 브라우저에서 리포트를 보실 수 있습니다.
    선불 충전이나 회차권 방식이 아닙니다.
  </p>
  <p>
    <strong>실물 상품이 없습니다.</strong> 배송되는 물건이 없고, 우편이나 오프라인으로 전달되는
    것도 없습니다. 상담사가 응대하지 않으며 모든 결과는 소프트웨어가 자동으로 만듭니다.
  </p>
  <p lang="en">
    ${BRAND_EN} is a web application. Customers buy access to the software, which
    analyses the text they enter and produces a written report shown in the browser.
    There is no physical product, nothing is shipped, and nothing is delivered offline.
    No human counsellor or advisor is involved.
  </p>
  <h2>환불 정책</h2>
  <p>
    이용권은 결제 즉시 지급되는 디지털 콘텐츠입니다. 한 번도 사용하지 않은 이용권은 기간
    제한 없이 전액 환불받을 수 있으며, 1:1 문의로 접수하면 처리해 드립니다. 사용을 시작한
    이용권은 결제 시 이 내용을 안내받고 동의한 경우 전자상거래 등에서의 소비자보호에 관한
    법률 제17조 제2항에 따라 청약철회가 제한됩니다.
  </p>
  <h2>문의</h2>
  <p>결제와 환불 문의: <a href="mailto:rlarlxo516@gmail.com">rlarlxo516@gmail.com</a> / 010-3449-6662</p>
`;

const BUSINESS = `
  <p>
    케이케이티랩스 / 대표 김도아 / 사업자등록번호 281-20-02774<br />
    통신판매업 신고 면제 대상(직전년도 거래 50회 미만)<br />
    대구광역시 달성군 다사읍 대실역북로1길 6, 106동 301호<br />
    <a href="mailto:rlarlxo516@gmail.com">rlarlxo516@gmail.com</a> / 010-3449-6662
  </p>
`;

const NAV = `
  <ul>
    <li><a href="/">홈</a></li>
    <li><a href="/pricing">이용권과 환불 정책</a></li>
    <li><a href="/terms">이용약관</a></li>
    <li><a href="/privacy">개인정보처리방침</a></li>
  </ul>
`;

const ROUTES = [
  {
    path: 'terms',
    title: `이용약관과 면책 고지 | ${BRAND}`,
    description: `${BRAND} 이용약관과 면책 고지. 서비스 내용, 이용권과 환불, 책임의 제한.`,
    body: docs.terms,
  },
  {
    path: 'privacy',
    title: `개인정보처리방침 | ${BRAND}`,
    description: `${BRAND} 개인정보처리방침. 수집 항목, 이용 목적, 국외 이전, 보유 기간, 파기.`,
    body: docs.privacy,
  },
  {
    path: 'pricing',
    title: `이용권과 환불 정책 | ${BRAND}`,
    description: `${BRAND} 이용권 가격과 환불 정책. 분석 리포트 1건 6,900원.`,
    body: PRICING,
  },
];

const shell = readFileSync(resolve(dist, 'index.html'), 'utf8');

for (const route of ROUTES) {
  const html = shell
    .replace(/<title>[^<]*<\/title>/, `<title>${route.title}</title>`)
    .replace(/(<meta\s+name="description"\s+content=")[^"]*(")/, `$1${route.description}$2`)
    .replace(
      /<noscript>[\s\S]*?<\/noscript>/,
      `<noscript>${route.body}${NAV}${BUSINESS}</noscript>`,
    );

  if (html === shell) {
    throw new Error(`prerender: ${route.path} 치환이 하나도 안 됐다 — index.html 구조가 바뀌었는지 확인`);
  }

  mkdirSync(resolve(dist, route.path), { recursive: true });
  writeFileSync(resolve(dist, route.path, 'index.html'), html, 'utf8');
  console.log(`prerendered /${route.path}`);
}
