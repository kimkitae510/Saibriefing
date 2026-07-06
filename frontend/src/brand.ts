// 서비스 이름은 여기 한 곳에서만 정한다. 화면, 약관, 공유 문구가 각자 문자열을 들고 있으면
// 이름을 바꿀 때 한둘이 남아 브랜드가 갈라진다.
//
// 상수로 못 박지 않고 빌드 변수로 두는 이유: 이름을 바꿀 때 화면, 약관, 메타 태그가 한
// 곳에서 같이 움직여야 하고, 도메인을 여러 벌로 서빙해야 할 때 빌드만 나누면 되기 때문이다.
//   VITE_BRAND=사이브리핑 VITE_BRAND_EN=Saibriefing npm run build
export const BRAND = import.meta.env.VITE_BRAND ?? '사이브리핑';

// 영문 문단에 쓸 표기. 한글 브랜드를 영어 문장에 그대로 넣으면 결제사 심사자가 읽지 못한다.
export const BRAND_EN = import.meta.env.VITE_BRAND_EN ?? 'Saibriefing';
