import { api } from './client';

// LET_GO(놓아주기)와 DATING(사귀는 중 잠금)은 폐기 — 만나는 중 사연은 게이트가 INSUFFICIENT
// 안내로 처리한다. 확률(POSSIBLE), 근거부족(INSUFFICIENT), 재회 성공(REUNITED — 전용 축하 화면).
export type Verdict = 'POSSIBLE' | 'INSUFFICIENT' | 'REUNITED';

// 고정 5요인의 판정 하나. 백엔드가 화면 표기용 한국어 라벨로 내려준다.
// level '중립' + evidence '근거 없음'이면 "알려주면 정확해져요" 안내로 바뀐다.
export interface FactorView {
  name: string; // "상대신호" 등 7종 — 내려오는 순서가 무게 순서
  level: '매우유리' | '유리' | '중립' | '불리' | '매우불리';
  evidence: string;
  rationale: string | null;
  stage: string | null; // 대체자 불리의 세분("정황"/"정착"). 그 외 null
}

// 관찰 포인트 — "이게 확인되면 판이 바뀐다".
export interface WatchView {
  point: string;
  effect: string;
}

// 관계 심리 — 확률과 무관한 "관계 이해용" 층. 라벨은 화면 표기용 한국어("불안형", "추구-회피").
// confidence("높음"/"중간"/"낮음")는 지금 화면에선 안 쓰지만 백엔드가 판정과 함께 저장한다.
export interface AttachmentStyle {
  label: string;
  confidence: string;
}

export interface RelationshipPsychology {
  attachment: {
    user: AttachmentStyle | null;
    partner: AttachmentStyle | null;
    description: string | null;
  } | null;
  interactionPattern: { label: string; confidence: string; description: string | null } | null;
  needConflict: { left: string | null; right: string | null; description: string | null } | null;
}

// 문진(사실 보강) 재판정의 변동내역 — 직전 확률 판정 대비 결정론 diff. 첫 판정은 null.
export interface ReadingDelta {
  probabilityFrom: number;
  probabilityTo: number;
  factors: { name: string; from: string; to: string }[];
}

// ── 정밀 판독(스토리북 리포트) ──────────────────────────────────────────────
// 사연별 미스터리 장이 본문이다 — 장 개수와 제목이 사연마다 다르다.
// 요인 어휘는 안 내려온다: 채점 내부 용어라 유저 지면에 꺼내지 않는다.

// 확률을 만든 진단 한 줄. 문장은 1호출이 쓰고, 순위와 등급은 백엔드가 붙인다 —
// 판독은 이 카드를 읽기만 하고 고쳐 쓰지 못한다.
export interface ReadingDiagnosis {
  key: string;
  label: string; // 상대신호, 이별사유, 이별결심 등
  group: 'CORE' | 'CONDITIONAL' | 'EXTRA';
  rank: number;
  level: '매우유리' | '유리' | '중립' | '불리' | '매우불리';
  // 확인됨 / 없다는 것이 확인됨 / 아직 부분만 — "아직 모르는 것"을 화면이 구분해 그린다
  evidenceState: 'CONFIRMED' | 'ABSENCE_CONFIRMED' | 'PARTIAL';
  headline: string; // 핵심 판정 한 문장
  reading: string; // 펼쳤을 때 나오는 근거
  factIds: string[];
}

// 장 하나가 질문 하나를 맡는다(v5). 처방은 행동 계획이 맡는다.
// eyebrow/title/answer/psychology는 v5 이전 저장분에만 있다 — 화면이 둘 다 그린다.
export interface ReadingChapter {
  question?: string; // 사연자가 속으로 묻는 말
  verdict?: string; // 그 물음에 대한 답
  reading: string;
  role?: string; // 채점용 메타데이터. 화면 비노출
  interpretationId: string | null; // 해석 교정 장이면 그 해석 참조. 화면 비노출
  evidenceIds: string[];
  // 아래는 옛 저장분 호환용
  eyebrow?: string | null;
  title?: string;
  answer?: string;
  chapterRole?: string;
  psychology?: { concept: string; reading: string } | null;
}

// 02의 끝점 — 남은 감정, 지금의 관계 선택, 다시 해도 달라질 거라는 기대는 서로 다른 층이라
// 한 문장으로 뭉치지 않고 셋을 따로 세운다. 옛 저장분엔 없어서 null이다.
export interface CurrentState {
  feeling: string;
  choice: string;
  repairBelief: string;
}

// 지금 어떻게 움직일지. 시점과 그 이유, 멈출 조건까지 한 덩이 —
// 행동만 있고 멈출 조건이 없으면 반응이 없을 때 계속 밀게 된다.
export interface ActionPlan {
  title: string;
  stance: string; // 내부 값(USE_EXISTING_EVENT 등). 화면 비노출
  answer: string;
  timing: string;
  whyThisTiming: string;
  nextMove: string; // 기다림이 끝나는 지점의 행동 하나. 멈추는 판이면 빈다
  mindset: string;
  goal: string;
  decisionValue: string; // 이 행동의 결과가 어떤 두 가능성을 가르는지
  doList: string[];
  stopCondition: string;
  ifClosed: string; // 멈출 조건이 실제로 나오면 그걸 무엇으로 읽을지
  avoid: string[];
}

// 지금은 어렵지만 시간이 지난 뒤 빈자리가 커질 사례 고유 근거가 있을 때만 내려온다.
// 없으면 null이다 — 모든 낮은 확률에 붙는 위로가 아니라서 자리 자체를 만들지 않는다.
export interface DelayedRegret {
  strength: 'MODERATE' | 'STRONG';
  headline: string | null;
  whyNotNow: string;
  whyLater: string;
  basis: string;
  limit: string | null;
  evidenceIds: string[];
}

// 편집자 호출의 산출 — 판독이 끝난 뒤 판을 확정한다(분석이 진단을 만든다).
// 4막 화면의 1막(마음 판독, 재회 판, 이유)과 2막(질문 답변)이 여기서 나온다.
export interface ReadingDecision {
  hook?: string | null; // 1장을 여는 첫 문장. 도입 전 저장분엔 없다
  prologue?: string | null; // 블록 구조 이전 통짜 서사 — 옛 저장분 표시용
  prologueEmpathy?: string | null; // 공감/현실 2필드 세대의 저장분 표시용
  prologueReality?: string | null;
  // 1장 본문 — 결론 문장형 소제목 + 본문(문단은 빈 줄 구분) 블록. 2단 파이프라인은
  // 1단 자유 분석을 여기에 배분한다
  prologueBlocks?: { subtitle: string; body: string }[] | null;
  // 행동 장 — 2단 파이프라인이 원석에서 배분한 자유 블록. 있으면 고정 필드(actionPlan)
  // 대신 이걸 그리고, 옛 저장분은 actionPlan을 쓴다
  actionBlocks?: { subtitle: string; body: string }[] | null;
  mind?: string | null; // 마음 장 통짜 문자열 — 블록 구조 이전 저장분 표시용
  // 마음 장 — 분석 장과 같은 소제목 블록. 새 생성은 이것만 채운다
  mindBlocks?: { subtitle: string; body: string }[] | null;
  innerVoice?: string | null; // 판독된 마음의 1인칭 번역(선택) — 실제 발언이 아니다
  outlookLevel: 'VERY_LOW' | 'LOW' | 'MID' | 'HIGH' | 'VERY_HIGH';
  outlookAnalysis?: string | null; // 왜 이 판인가 — 하나의 흐름으로 쓴 2~4문장
  outlookScore: number; // 코드가 level에서 계산 — LLM 숫자가 아니라 재현된다
  // 재회 가능성 논증 카드 — 소제목이 판정 결론 문장, 본문이 가능성을 왜 움직이는지,
  // direction이 올림/내림. 새 생성은 이것만 채운다 (direction 없는 건 도입 직전 저장분)
  // pivot은 등급을 만든 결정적 판단 — 화면은 배지 대신 그룹 맨 위 배치로 말한다
  verdictBlocks?: {
    subtitle: string; body: string; direction?: 'UP' | 'DOWN' | null; pivot?: boolean | null;
  }[] | null;
  // 이 판을 만든 이유 카드 — 카드 세대 저장분 표시용. 화면이 방향별로 묶어 그린다
  reasons: { label: string; direction: 'UP' | 'DOWN'; reading: string }[];
  answers: { question: string; answer: string }[]; // 당신이 궁금했던 것
  // 소견서 본문 — 분석 문단 전부가 원문 순서대로. 판을 가른 문단에만 direction이 붙고,
  // subtitle은 대목의 첫 문단에만, pivot은 등급을 만든 대목. 있으면 카드 대신 이걸 그린다
  readingBlocks?: {
    subtitle: string; body: string; direction?: 'UP' | 'DOWN' | 'NONE' | null;
    pivot?: boolean | null;
    keyword?: string | null; // 정리표 키워드 중 이 문단에서 온 것 — 라벨 문구가 되고 위 카드가 가리킨다
  }[] | null;
  // 게이지 아래 정리표 — 분류 호출이 완성된 분석에서 뽑은 것. name은 이 이별을 부르는 한 줄,
  // up/down은 가능성을 올리고 내리는 것의 키워드와 그 문단 번호, why는 지금 이 등급인 이유.
  // 옛 저장분은 null
  // note는 그 이름이 왜 올리는지 또는 내리는지 한 줄 — 카드 본문
  summary?: {
    name: string;
    up: { keyword: string; para?: number | null; note?: string | null }[];
    down: { keyword: string; para?: number | null; note?: string | null }[];
    why: string;
    types?: string[] | null; // 이 사연에 겹친 이별의 유형 한 줄씩 — 총평 아래, 옛 저장분은 없음
  } | null;
}

// v12 — 4막: 어디에 있나(decision) → 궁금했던 것(answers) → 중요했던 것(chapters) → 행동.
export interface StoryReport {
  diagnosisSummary: string;
  // 시간효과(1호출 판정). 화면 비노출 — 시간 얘기는 delayedRegret 마크가 맡고,
  // 이 값은 행동 계획의 타이밍 재료로 판독에만 들어간다
  timeInsight: { label: string; headline: string; reading: string } | null;
  diagnosis: ReadingDiagnosis[];
  analysisSectionTitle: string; // 심층 장 묶음의 큰 질문
  analysisChapters: ReadingChapter[];
  currentState: CurrentState | null; // 02 끝의 세 축 종합. 옛 저장분은 null
  synthesis?: string | null; // baseline 모드의 종합. 축을 미리 정하지 않고 한 덩이로 온다
  delayedRegret: DelayedRegret | null;
  actionPlan: ActionPlan | null; // 결정 호출이 채운다
  decision?: ReadingDecision | null; // 결정 도입 전 저장분엔 없다
}

export interface ReadingView {
  report: StoryReport;
  delta: ReadingDelta | null;
  createdAt: string | null;
}

export interface AssessmentResponse {
  verdict: Verdict;
  probability: number | null; // 잠금 판정이면 null
  breakupType: string | null; // 이별 유형 라벨("충동형"). 과거(v1) 데이터와 잠금 판정은 null
  typeEvidence: string | null;
  jumpRule: string | null; // 점프 라벨("유저통보미련흔적" 등). 유저 통보 판이면 유형 대신 이게 대역을 정함
  relapseRisk: string | null; // 유지 전망 라벨("높음")
  relapseReason: string | null;
  // 관계 심리(애착 경향, 관계 패턴, 욕구 충돌). 정보가 부족한 진단과 옛 데이터는 null
  relationshipPsychology?: RelationshipPsychology | null;
  reason: string;
  factors: FactorView[];
  watchFor: WatchView[];
  // 상담자가 물었는데 답이 안 온 질문. 비어 있으면 요인 슬롯의 고정 문구로 폴백한다.
  unansweredQuestions?: string[];
  createdAt: string | null; // INSUFFICIENT는 저장 안 돼서 null
  // 연속 실패 쿨다운으로 막힌 응답에만 채워진다. 남은 초(시각이 아니라)라서
  // 기기 시계가 틀어져 있어도 카운트다운이 어긋나지 않는다.
  retryAfterSeconds?: number | null;
  // 정밀 판독. 확률 있는 일반 판정에만 붙고, 판독 생성이 실패한 판정은 null(판정부만 그린다).
  reading?: ReadingView | null;
}

// 지금 대화를 근거로 새 분석을 실행한다(POST). INSUFFICIENT면 저장되지 않는다.
export async function runAssessment(storyId: number): Promise<AssessmentResponse> {
  const { data } = await api.post<AssessmentResponse>(`/api/stories/${storyId}/assessments`);
  return data;
}

// 저장된 분석 이력(최신순). 05 히스토리에서 사용.
export async function getAssessments(storyId: number): Promise<AssessmentResponse[]> {
  const { data } = await api.get<AssessmentResponse[]>(`/api/stories/${storyId}/assessments`);
  return data;
}

// 분석이 도는 동안의 단계. DIAGNOSIS(판정) → READING(심층 판독) → DECISION(판 결정).
export type AssessmentStage = 'DIAGNOSIS' | 'READING' | 'DECISION' | null;

export async function getAssessmentProgress(storyId: number): Promise<AssessmentStage> {
  const { data } = await api.get<{ stage: AssessmentStage }>(
    `/api/stories/${storyId}/assessments/progress`,
  );
  return data.stage ?? null;
}

// 재회 성공 잠금을 유저가 직접 번복한다(다시 헤어졌거나 분석이 오해했을 수 있음).
// 잠금 판정이 지워지고 직전 확률 분석이 돌아온다(없으면 null — 첫 분석 안내로).
export async function confirmBreakup(storyId: number): Promise<AssessmentResponse | null> {
  const { data } = await api.post<AssessmentResponse | ''>(
    `/api/stories/${storyId}/assessments/confirm-breakup`,
  );
  return data || null;
}

// "상대의 재회 제안 유효(100%)" 확정을 유저가 직접 번복한다(제안이 아니었거나 없던 일이 됨).
// 원장에 정정이 남고, 저장된 신호의 재합산 값으로 즉시 되돌린 결과가 돌아온다(재분석 불필요).
export async function retractOffer(storyId: number): Promise<AssessmentResponse> {
  const { data } = await api.post<AssessmentResponse>(
    `/api/stories/${storyId}/assessments/retract-offer`,
  );
  return data;
}
