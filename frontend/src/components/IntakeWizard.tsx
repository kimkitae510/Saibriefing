import type {
  BreakupInitiator,
  ClingReaction,
  ContactMode,
  IntakeGender,
  LastActionBeforeCut,
  NewRelationOverlap,
  PartnerAction,
  PartnerNewRelation,
  PauseContactRule,
  PausePlan,
  PreBreakupChange,
  PriorReunion,
  PriorReunionGap,
  PriorReunionPath,
  RelationState,
  RepeatBreakupPattern,
  RepeatSeverity,
  SelfEndReason,
  StoryIntake,
} from '../api/intake';
import { useEffect, useRef } from 'react';
import { EMPTY_INTAKE } from '../api/intake';
import styles from './IntakeWizard.module.css';

// 사연을 받기 전에 한 장씩 넘기며 받는 것들. 열둘을 한 화면에 세우면 접수증이 되고,
// 사연을 다 쓴 뒤에 받으면 털어놓은 사람에게 폼을 내미는 꼴이 된다.
// 여기 남긴 것은 셋을 다 만족하는 것뿐이다 — 사연을 안 읽고도 물을 수 있고, 답이 하나로
// 정해지고, 안 물으면 상담자가 첫 턴을 되묻는 데 써버리는 것.
// 이별 사유는 안 묻는다: 답이 하나가 아닌 것을 사연도 쓰기 전에 보기로 주면, 유저가 자기
// 상황을 보기에서 고르고 그게 그대로 오진이 된다. 가지 질문도 사유가 아니라 앞에서 받은
// 사실 답(누가 먼저, 연락 상태, 새 사람)에 건다.
// 질문마다 왜 묻는지 한 줄을 붙인다 — 이유가 보이면 답하는 쪽이 접수가 아니라 상담으로 읽는다.

export type StepId =
  | 'age'
  | 'gender'
  | 'dating'
  | 'relation'
  | 'pausePlan'
  | 'pauseContact'
  | 'since'
  | 'priorReunion'
  | 'repeatPattern'
  | 'repeatSeverity'
  | 'priorPath'
  | 'priorGap'
  | 'initiator'
  | 'selfEndReason'
  | 'contactMode'
  | 'lastAction'
  | 'cling'
  | 'preChange'
  | 'partnerHasNew'
  | 'overlap'
  | 'partnerActions';

// 숫자를 문자열로 들고 있는다 — number로 받으면 "1"을 지우는 중간 상태가 0으로 굳어,
// 안 지워지는 칸이 된다.
export interface IntakeDraft {
  userAge: string;
  partnerAge: string;
  userGender: IntakeGender | null;
  datingYears: string;
  datingMonths: string;
  // 헤어짐인지 시간 갖기인지. 시간 갖기면 정한 기간과 연락 규칙을 이어서 묻는다.
  relationState: RelationState | null;
  pausePlan: PausePlan | null;
  pauseContactRule: PauseContactRule | null;
  sinceValue: string;
  sinceUnit: SinceUnit;
  priorReunion: PriorReunion | null;
  repeatBreakupPattern: RepeatBreakupPattern | null;
  repeatSeverity: RepeatSeverity | null;
  priorReunionPath: PriorReunionPath | null;
  priorReunionGap: PriorReunionGap | null;
  initiator: BreakupInitiator | null;
  selfEndReason: SelfEndReason | null;
  contactMode: ContactMode | null;
  lastActionBeforeCut: LastActionBeforeCut | null;
  clingReaction: ClingReaction | null;
  preBreakupChange: PreBreakupChange | null;
  partnerHasNew: PartnerNewRelation | null;
  newRelationOverlap: NewRelationOverlap | null;
  partnerActions: PartnerAction[];
  // "기타"를 고른 칸의 직접 입력. 키는 칸 이름. 기타를 풀면 글은 남겨두되 안 보낸다.
  otherAnswers: Record<string, string>;
}

export const EMPTY_DRAFT: IntakeDraft = {
  userAge: '',
  partnerAge: '',
  userGender: null,
  datingYears: '',
  datingMonths: '',
  relationState: null,
  pausePlan: null,
  pauseContactRule: null,
  sinceValue: '',
  sinceUnit: 'MONTH',
  priorReunion: null,
  repeatBreakupPattern: null,
  repeatSeverity: null,
  priorReunionPath: null,
  priorReunionGap: null,
  initiator: null,
  selfEndReason: null,
  contactMode: null,
  lastActionBeforeCut: null,
  clingReaction: null,
  preBreakupChange: null,
  partnerHasNew: null,
  newRelationOverlap: null,
  partnerActions: [],
  otherAnswers: {},
};

// 가지가 열리는 조건. 여기 한 곳에서만 판단한다 — 단계 목록과 저장 둘 다 이걸 본다.
// 목록만 보고 저장을 안 거르면, 답을 바꿔 가지가 닫힌 뒤에도 옛 가지 답이 서버로 간다.
const asksRepeatPattern = (d: IntakeDraft) =>
  d.priorReunion === 'ONCE' || d.priorReunion === 'MANY';
const asksSelfEndReason = (d: IntakeDraft) => d.initiator === 'SELF';
const asksLastAction = (d: IntakeDraft) =>
  d.contactMode === 'READ_NO_REPLY' || d.contactMode === 'BLOCKED';
const asksOverlap = (d: IntakeDraft) => d.partnerHasNew === 'CONFIRMED';
const asksPause = (d: IntakeDraft) => d.relationState === 'ON_BREAK';

// 답에 따라 단계가 늘고 준다. 가지는 조건이 된 질문 바로 뒤에 끼운다 — 뒤로 몰면
// 무엇에 대한 질문인지 잊는다.
// 관계 상태는 경과보다 앞이다 — "헤어진 지"를 물을지 "시간을 갖기 시작한 지"를 물을지가 여기서 갈린다.
export function intakeSteps(draft: IntakeDraft): StepId[] {
  const steps: StepId[] = ['age', 'gender', 'dating', 'relation'];
  if (asksPause(draft)) steps.push('pausePlan', 'pauseContact');
  steps.push('since', 'priorReunion');
  if (asksRepeatPattern(draft)) steps.push('repeatPattern', 'repeatSeverity', 'priorPath', 'priorGap');
  steps.push('initiator');
  if (asksSelfEndReason(draft)) steps.push('selfEndReason');
  steps.push('contactMode');
  if (asksLastAction(draft)) steps.push('lastAction');
  steps.push('cling', 'preChange', 'partnerHasNew');
  if (asksOverlap(draft)) steps.push('overlap');
  steps.push('partnerActions');
  return steps;
}

// 경과는 숫자 한 칸과 단위 하나로 받는다. 개월과 일 두 칸으로 받으면 3주 된 사람은 21일로,
// 2년 된 사람은 24개월로 환산해야 했다. 사람이 말하는 단위 그대로 받고 일 수로는 여기서만 접는다.
// 개월과 년은 30일, 365일 근사 — 판독이 보는 경계(5주, 1개월, 3개월)를 가르기엔 충분하다.
export type SinceUnit = 'DAY' | 'WEEK' | 'MONTH' | 'YEAR';
const SINCE_UNITS: { value: SinceUnit; label: string; days: number }[] = [
  { value: 'DAY', label: '일', days: 1 },
  { value: 'WEEK', label: '주', days: 7 },
  { value: 'MONTH', label: '개월', days: 30 },
  { value: 'YEAR', label: '년', days: 365 },
];
const sinceUnit = (unit: SinceUnit) => SINCE_UNITS.find((u) => u.value === unit) ?? SINCE_UNITS[2];

function sinceToDays(value: string, unit: SinceUnit): number | null {
  if (value === '') return null;
  return (Number(value) || 0) * sinceUnit(unit).days;
}

// 기간은 두 칸으로 받아 하나로 합친다. 단위를 고르게 하면 탭이 한 번 더 들어가고,
// 한 칸으로 받으면 "2년 3개월"을 27로 환산하는 일을 유저에게 시키게 된다.
// 둘 다 비면 0이 아니라 null이다 — 안 물어본 것과 0을 섞으면 진단이 조용히 기운다.
function merge(big: string, small: string, unit: number): number | null {
  if (big === '' && small === '') return null;
  return (Number(big) || 0) * unit + (Number(small) || 0);
}

export function draftToIntake(draft: IntakeDraft): StoryIntake {
  return {
    ...EMPTY_INTAKE,
    userAge: draft.userAge === '' ? null : Number(draft.userAge),
    partnerAge: draft.partnerAge === '' ? null : Number(draft.partnerAge),
    userGender: draft.userGender,
    datingMonths: merge(draft.datingYears, draft.datingMonths, 12),
    relationState: draft.relationState,
    pausePlan: asksPause(draft) ? draft.pausePlan : null,
    pauseContactRule: asksPause(draft) ? draft.pauseContactRule : null,
    daysSinceBreakup: sinceToDays(draft.sinceValue, draft.sinceUnit),
    priorReunion: draft.priorReunion,
    repeatBreakupPattern: asksRepeatPattern(draft) ? draft.repeatBreakupPattern : null,
    repeatSeverity: asksRepeatPattern(draft) ? draft.repeatSeverity : null,
    priorReunionPath: asksRepeatPattern(draft) ? draft.priorReunionPath : null,
    priorReunionGap: asksRepeatPattern(draft) ? draft.priorReunionGap : null,
    initiator: draft.initiator,
    selfEndReason: asksSelfEndReason(draft) ? draft.selfEndReason : null,
    contactMode: draft.contactMode,
    lastActionBeforeCut: asksLastAction(draft) ? draft.lastActionBeforeCut : null,
    clingReaction: draft.clingReaction,
    preBreakupChange: draft.preBreakupChange,
    partnerHasNew: draft.partnerHasNew,
    newRelationOverlap: asksOverlap(draft) ? draft.newRelationOverlap : null,
    partnerActions: draft.partnerActions,
    otherAnswers: activeOthers(draft),
  };
}

// 기타를 고른 채로 있는 칸의 글만 보낸다. 보기를 바꿔 기타가 풀렸거나 가지가 닫힌 칸의 글은
// 화면에는 남아 있어도 서버로 안 간다.
function activeOthers(draft: IntakeDraft): Record<string, string> {
  const picked: Record<string, string | null | undefined> = {
    relationState: draft.relationState,
    pausePlan: asksPause(draft) ? draft.pausePlan : null,
    pauseContactRule: asksPause(draft) ? draft.pauseContactRule : null,
    priorReunion: draft.priorReunion,
    repeatBreakupPattern: asksRepeatPattern(draft) ? draft.repeatBreakupPattern : null,
    repeatSeverity: asksRepeatPattern(draft) ? draft.repeatSeverity : null,
    priorReunionPath: asksRepeatPattern(draft) ? draft.priorReunionPath : null,
    priorReunionGap: asksRepeatPattern(draft) ? draft.priorReunionGap : null,
    initiator: draft.initiator,
    selfEndReason: asksSelfEndReason(draft) ? draft.selfEndReason : null,
    contactMode: draft.contactMode,
    lastActionBeforeCut: asksLastAction(draft) ? draft.lastActionBeforeCut : null,
    clingReaction: draft.clingReaction,
    preBreakupChange: draft.preBreakupChange,
    partnerHasNew: draft.partnerHasNew,
    newRelationOverlap: asksOverlap(draft) ? draft.newRelationOverlap : null,
    partnerActions: draft.partnerActions.includes('OTHER') ? 'OTHER' : null,
  };
  const out: Record<string, string> = {};
  for (const [field, value] of Object.entries(picked)) {
    const text = (draft.otherAnswers[field] ?? '').trim();
    if (value === 'OTHER' && text) out[field] = text;
  }
  return out;
}

// 하나도 안 채운 채 넘어갔으면 저장을 아예 안 보낸다 — 전부 null로 덮으면 서버가
// "냈다"로 표시해, 나중에 물어야 할 자리에서 이미 받은 것으로 읽힌다.
export function hasAnyAnswer(draft: IntakeDraft): boolean {
  return Object.entries(draft).some(([key, v]) => {
    if (key === 'otherAnswers') return false;
    return Array.isArray(v) ? v.length > 0 : v !== '' && v !== null;
  });
}

type Option<T extends string> = { value: T; label: string };

// 보기 문구는 화면에만 있다. 저장값은 서버 enum 이름이라 문구를 고쳐도 데이터는 안 흔들린다.
// 모든 목록의 끝은 "잘 모르겠다"와 "기타"다. 모름을 안 두면 유저가 아무 보기나 고르고 그게
// 사실로 저장되며, 기타를 안 두면 보기에 없는 상황이 가장 비슷한 보기로 뭉개진다.
const UNSURE_OTHER = [
  { value: 'UNSURE', label: '잘 모르겠다' },
  { value: 'OTHER', label: '기타' },
] as const;
const UNKNOWN_OTHER = [
  { value: 'UNKNOWN', label: '잘 모르겠다' },
  { value: 'OTHER', label: '기타' },
] as const;

const RELATION_STATES: Option<RelationState>[] = [
  { value: 'BROKEN_UP', label: '헤어졌다' },
  { value: 'ON_BREAK', label: '시간을 갖는 중이다' },
  // 애매함은 모름이 아니라 그 자체가 정보다 — 헤어지자는 말 없이 끊긴 사이가 여기다
  { value: 'UNCLEAR', label: '헤어진 건지 애매하다' },
  { value: 'OTHER', label: '기타' },
];

const PAUSE_PLANS: Option<PausePlan>[] = [
  { value: 'NONE_SET', label: '기간을 정하지 않았다' },
  { value: 'WITHIN_TWO_WEEKS', label: '2주 안' },
  { value: 'ABOUT_A_MONTH', label: '한 달 정도' },
  { value: 'LONGER', label: '한 달보다 길게' },
  ...UNSURE_OTHER,
];

const PAUSE_CONTACT_RULES: Option<PauseContactRule>[] = [
  { value: 'NO_CONTACT', label: '연락하지 않기로 했다' },
  { value: 'FREE', label: '연락은 자유롭게 하기로 했다' },
  { value: 'NOT_DISCUSSED', label: '따로 정한 게 없다' },
  ...UNSURE_OTHER,
];

const PRIOR_REUNIONS: Option<PriorReunion>[] = [
  { value: 'NONE', label: '이번이 처음입니다' },
  { value: 'ONCE', label: '헤어졌다 다시 만난 적이 한 번 있습니다' },
  { value: 'MANY', label: '두 번 이상 있습니다' },
  ...UNSURE_OTHER,
];

const REPEAT_PATTERNS: Option<RepeatBreakupPattern>[] = [
  { value: 'SAME_ISSUE', label: '같은 문제로' },
  { value: 'DIFFERENT', label: '매번 다른 이유로' },
  ...UNSURE_OTHER,
];

const REPEAT_SEVERITIES: Option<RepeatSeverity>[] = [
  { value: 'MORE_FINAL', label: '지난번보다 훨씬 단호했다' },
  { value: 'SIMILAR', label: '지난번과 비슷했다' },
  { value: 'LIGHTER', label: '지난번보다 가벼웠다' },
  ...UNSURE_OTHER,
];

const PRIOR_PATHS: Option<PriorReunionPath>[] = [
  { value: 'PARTNER_RETURNED', label: '상대가 먼저 돌아왔다' },
  { value: 'I_HELD_ON', label: '내가 붙잡아서' },
  { value: 'DRIFTED_BACK', label: '자연스럽게 다시 연락이 닿았다' },
  ...UNSURE_OTHER,
];

const PRIOR_GAPS: Option<PriorReunionGap>[] = [
  { value: 'WITHIN_WEEK', label: '일주일 안' },
  { value: 'WITHIN_MONTH', label: '한 달 안' },
  { value: 'WITHIN_QUARTER', label: '석 달 안' },
  { value: 'WITHIN_HALF_YEAR', label: '반년 안' },
  { value: 'LONGER', label: '반년보다 오래' },
  ...UNSURE_OTHER,
];

const INITIATORS: Option<BreakupInitiator>[] = [
  { value: 'PARTNER', label: '상대가 먼저' },
  { value: 'SELF', label: '내가 먼저' },
  // 겉으로는 내가 통보한 이별인데 구도는 상대 통보인 판. 이걸 가르지 않으면 정말로 내
  // 마음이 식어서 찬 사례가 붙는다.
  { value: 'PUSHED', label: '말은 내가 꺼냈지만 상대가 그렇게 만들었다' },
  ...UNKNOWN_OTHER,
];

const SELF_END_REASONS: Option<SelfEndReason>[] = [
  { value: 'IMPULSIVE', label: '홧김에, 충동적으로' },
  { value: 'WORN_OUT', label: '지쳐서, 쌓여서' },
  { value: 'EXTERNAL', label: '외부 사정 때문에' },
  { value: 'TEST', label: '떠보려다' },
  ...UNSURE_OTHER,
];

const CONTACT_MODES: Option<ContactMode>[] = [
  { value: 'PARTNER_REACHES', label: '상대가 먼저 연락해 온다' },
  { value: 'MUTUAL', label: '서로 주고받는다' },
  { value: 'I_INITIATE', label: '내가 보내면 답은 온다' },
  { value: 'READ_NO_REPLY', label: '읽고 답이 없다' },
  { value: 'NONE', label: '아예 없다' },
  { value: 'BLOCKED', label: '차단당했다' },
  ...UNKNOWN_OTHER,
];

const LAST_ACTIONS: Option<LastActionBeforeCut>[] = [
  { value: 'BEGGED', label: '많이 매달렸다' },
  { value: 'ANGRY', label: '화내거나 다퉜다' },
  { value: 'NORMAL', label: '평범하게 연락했다' },
  ...UNKNOWN_OTHER,
];

const CLING_REACTIONS: Option<ClingReaction>[] = [
  { value: 'CLUNG_FIRM', label: '매달렸는데 상대는 단호했다' },
  { value: 'CLUNG_WAVERED', label: '매달리니 상대가 흔들렸다' },
  { value: 'LITTLE', label: '조금 하다 말았다' },
  { value: 'NONE', label: '잡지 않았다' },
  ...UNSURE_OTHER,
];

const PRE_BREAKUP_CHANGES: Option<PreBreakupChange>[] = [
  { value: 'SUDDEN', label: '전조 없이 갑자기' },
  { value: 'FIGHTS_INCREASED', label: '몇 주 전부터 싸움이 늘었다' },
  { value: 'GRADUAL_COOLING', label: '몇 달에 걸쳐 서서히 식었다' },
  { value: 'REPEATED_WARNINGS', label: '전부터 여러 번 힘들다고 했다' },
  { value: 'SINGLE_INCIDENT', label: '잘 지내다 사건 하나로' },
  ...UNSURE_OTHER,
];

// "없음"과 "모름"을 반드시 가른다 — 확인 안 된 것을 없음으로 저장하면 진단이 유리한 쪽으로
// 조용히 기운다. 그래서 기본값도 모름이 아니라 아예 비워둔다(고르게 한다).
const PARTNER_NEW: Option<PartnerNewRelation>[] = [
  { value: 'CONFIRMED', label: '있는 걸 확인했다' },
  { value: 'DENIED', label: '없는 걸 확인했다' },
  ...UNKNOWN_OTHER,
];

const OVERLAPS: Option<NewRelationOverlap>[] = [
  { value: 'DURING', label: '사귈 때부터 겹쳤다' },
  { value: 'RIGHT_AFTER', label: '헤어지자마자' },
  { value: 'LATER', label: '한참 뒤에' },
  ...UNSURE_OTHER,
];

const PARTNER_ACTIONS: Option<PartnerAction>[] = [
  { value: 'REACHED_OUT', label: '먼저 연락해 왔다' },
  { value: 'ASKED_TO_MEET', label: '만나자고 했다' },
  { value: 'SNS_TRACE', label: 'SNS 흔적을 남긴다' },
  { value: 'BELONGINGS_ONLY', label: '물건이나 정리 얘기만 했다' },
  { value: 'CUT_OFF', label: '차단하거나 정리했다' },
  { value: 'OTHER', label: '기타' },
  { value: 'NOTHING', label: '아무것도 없었다' },
  { value: 'UNKNOWN', label: '잘 모르겠다' },
];

// 질문과 그 이유. 이유는 유저에게 하는 말이라 무엇을 계산하는지가 아니라 왜 답이 판을
// 가르는지를 적는다.
// 고른 표시(체크)가 눈에 들어올 만큼은 머물고 넘어간다. 180이면 표시가 뜨기 전에 화면이 바뀐다.
const ADVANCE_DELAY = 260;

// 고른 줄의 표시. 색 조각으로 줄을 칠하지 않고 오른쪽 끝에 얇은 체크 하나 — iOS 목록의 선택 문법이다.
function Check() {
  return (
    <svg className={styles.check} width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="M5 12.5l4.2 4.2L19 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

// 문진 끝 되짚기 화면에 쓰는 요약. 답한 것만 묶고, 안 답한 묶음은 줄 자체를 내지 않는다.
export function recapLines(draft: IntakeDraft): [string, string][] {
  const label = (options: Option<string>[], value: string | null) =>
    options.find((o) => o.value === value)?.label ?? '';
  const other = (field: string, value: string | null, text: string) =>
    value === 'OTHER' && (draft.otherAnswers[field] ?? '').trim()
      ? draft.otherAnswers[field].trim()
      : text;
  const join = (parts: string[]) => parts.filter(Boolean).join(', ');
  const span = (big: string, small: string, u1: string, u2: string) =>
    [big && big + u1, small && small + u2].filter(Boolean).join(' ');
  const rows: [string, string][] = [];
  const two = join([
    draft.userAge && draft.userAge + '세',
    draft.userGender === 'MALE' ? '남성' : draft.userGender === 'FEMALE' ? '여성' : '',
    draft.partnerAge && '상대 ' + draft.partnerAge + '세',
    span(draft.datingYears, draft.datingMonths, '년', '개월'),
  ]);
  if (two) rows.push(['두 사람', two]);
  const since = draft.sinceValue && draft.sinceValue + sinceUnit(draft.sinceUnit).label;
  const brk = join([
    other('relationState', draft.relationState, label(RELATION_STATES, draft.relationState)),
    asksPause(draft) ? other('pausePlan', draft.pausePlan, label(PAUSE_PLANS, draft.pausePlan)) : '',
    asksPause(draft) ? other('pauseContactRule', draft.pauseContactRule, label(PAUSE_CONTACT_RULES, draft.pauseContactRule)) : '',
    since && since + ' 전',
    other('initiator', draft.initiator, label(INITIATORS, draft.initiator)),
    asksSelfEndReason(draft) ? other('selfEndReason', draft.selfEndReason, label(SELF_END_REASONS, draft.selfEndReason)) : '',
    other('preBreakupChange', draft.preBreakupChange, label(PRE_BREAKUP_CHANGES, draft.preBreakupChange)),
  ]);
  if (brk) rows.push([asksPause(draft) ? '멈춤' : '이별', brk]);
  const rep = join([
    other('priorReunion', draft.priorReunion, label(PRIOR_REUNIONS, draft.priorReunion)),
    asksRepeatPattern(draft) ? other('repeatBreakupPattern', draft.repeatBreakupPattern, label(REPEAT_PATTERNS, draft.repeatBreakupPattern)) : '',
    asksRepeatPattern(draft) ? other('repeatSeverity', draft.repeatSeverity, label(REPEAT_SEVERITIES, draft.repeatSeverity)) : '',
    asksRepeatPattern(draft) ? other('priorReunionPath', draft.priorReunionPath, label(PRIOR_PATHS, draft.priorReunionPath)) : '',
    asksRepeatPattern(draft) ? other('priorReunionGap', draft.priorReunionGap, label(PRIOR_GAPS, draft.priorReunionGap)) : '',
  ]);
  if (rep) rows.push(['반복', rep]);
  const now = join([
    other('contactMode', draft.contactMode, label(CONTACT_MODES, draft.contactMode)),
    asksLastAction(draft) ? other('lastActionBeforeCut', draft.lastActionBeforeCut, label(LAST_ACTIONS, draft.lastActionBeforeCut)) : '',
    other('clingReaction', draft.clingReaction, label(CLING_REACTIONS, draft.clingReaction)),
  ]);
  if (now) rows.push(['지금', now]);
  const partner = join([
    other('partnerHasNew', draft.partnerHasNew, label(PARTNER_NEW, draft.partnerHasNew)),
    asksOverlap(draft) ? other('newRelationOverlap', draft.newRelationOverlap, label(OVERLAPS, draft.newRelationOverlap)) : '',
    join(draft.partnerActions.map((a) => (a === 'OTHER' ? other('partnerActions', 'OTHER', '기타') : label(PARTNER_ACTIONS, a)))),
  ]);
  if (partner) rows.push(['상대', partner]);
  return rows;
}

// 열두 질문을 네 장으로 읽히게 하는 이름. 되짚기 화면의 줄 이름과 같다 — 답할 때 본 장 이름을
// 되짚을 때 다시 보게 되면 열두 개가 아니라 넷을 답한 것으로 기억된다.
const SECTION: Record<StepId, string> = {
  age: '두 사람',
  gender: '두 사람',
  dating: '두 사람',
  relation: '이별',
  pausePlan: '이별',
  pauseContact: '이별',
  since: '이별',
  priorReunion: '이별',
  repeatPattern: '이별',
  repeatSeverity: '이별',
  priorPath: '이별',
  priorGap: '이별',
  initiator: '이별',
  selfEndReason: '이별',
  contactMode: '지금',
  lastAction: '지금',
  cling: '지금',
  preChange: '이별',
  partnerHasNew: '상대',
  overlap: '상대',
  partnerActions: '상대',
};

const TEXT: Record<StepId, { ask: string; why: string }> = {
  age: {
    ask: '두 분 나이가 어떻게 되십니까',
    why: '같은 일도 스물셋과 서른다섯은 다르게 읽습니다.',
  },
  gender: {
    ask: '성별이 어떻게 되십니까',
    why: '같은 상황을 받아들이는 방식이 갈립니다.',
  },
  dating: {
    ask: '얼마나 만나셨습니까',
    why: '만난 기간에 따라 접근이 달라집니다.',
  },
  relation: {
    ask: '지금 두 분은 어떤 상태입니까',
    why: '헤어진 것과 시간을 갖는 것은 같은 침묵이라도 뜻이 다릅니다.',
  },
  pausePlan: {
    ask: '시간을 갖기로 하면서 기간은 정하셨습니까',
    why: '기간 없는 시간 갖기는 상대가 결론을 미룬 것이라 읽는 법이 다릅니다.',
  },
  pauseContact: {
    ask: '그동안 연락은 어떻게 하기로 하셨습니까',
    why: '규칙대로 안 하는 연락과 규칙 없이 끊긴 연락은 다릅니다.',
  },
  since: {
    ask: '헤어진 지 얼마나 되셨습니까',
    why: '경과에 따라 지금 할 일이 달라집니다.',
  },
  priorReunion: {
    ask: '이 분과 헤어진 게 처음이십니까',
    why: '헤어졌다 다시 만난 적이 있는 사이는 판 자체가 다릅니다. 헤어지자는 말만 오간 건 세지 않습니다.',
  },
  repeatPattern: {
    ask: '그때와 같은 문제로 헤어지셨습니까',
    why: '같은 문제가 되풀이된 이별과 매번 다른 이유로 끝난 이별은 다시 만나도 갈 길이 다릅니다.',
  },
  repeatSeverity: {
    ask: '이번 이별은 지난번과 비교하면 어땠습니까',
    why: '같은 이유라도 무게가 다르면 판이 다릅니다. 지난번엔 며칠 냉전이었는데 이번엔 차단이면 사이클이 아니라 끝입니다.',
  },
  priorPath: {
    ask: '지난번엔 어떻게 다시 만나게 되셨습니까',
    why: '상대가 먼저 돌아온 적이 있는 판과 붙잡아서 붙인 판은 이번에 기다릴지 움직일지가 정반대입니다.',
  },
  priorGap: {
    ask: '지난번엔 헤어지고 얼마 만에 다시 만나셨습니까',
    why: '지금 경과와 나란히 놓으면 아직 사이클 안인지 이미 넘었는지가 갈립니다. 여러 번이면 가장 최근으로 답하시면 됩니다.',
  },
  initiator: {
    ask: '먼저 이별을 원한 쪽은 누구입니까',
    why: '말을 꺼낸 쪽과 원한 쪽이 다를 때가 많고, 그게 판을 가릅니다.',
  },
  selfEndReason: {
    ask: '그때 왜 헤어지자고 하셨습니까',
    why: '홧김에 끊긴 판과 지쳐서 끝난 판은 되돌리는 길이 다릅니다.',
  },
  contactMode: {
    ask: '지금 연락은 어떻습니까',
    why: '가능성을 가장 크게 가르는 부분입니다.',
  },
  lastAction: {
    ask: '끊기기 직전에 마지막으로 무엇을 하셨습니까',
    why: '끊긴 이유가 행동 때문인지 마음 때문인지가 여기서 갈립니다.',
  },
  cling: {
    ask: '헤어진 뒤 붙잡아 보셨습니까',
    why: '붙잡았을 때의 반응이 상대 마음의 온도계입니다.',
  },
  preChange: {
    ask: '헤어지기 직전 관계는 어땠습니까',
    why: '충동으로 끊긴 판과 소진된 판은 원인이 다릅니다.',
  },
  partnerHasNew: {
    ask: '상대에게 새로운 사람이 있습니까',
    why: '확인 안 된 것을 없다고 치면 결과가 유리한 쪽으로 기웁니다.',
  },
  overlap: {
    ask: '그 사람은 언제부터입니까',
    why: '겹친 시점에 따라 이별의 성격이 달라집니다.',
  },
  partnerActions: {
    ask: '헤어진 뒤 상대가 먼저 한 일이 있습니까',
    why: '지금 연락이 없어도 한 번 먼저 온 판은 다릅니다. 가장 큰 것 하나만 고르면 됩니다.',
  },
};

// 시간을 갖는 중이면 같은 칸을 다른 말로 묻는다. 저장 칸은 같다 — 서버가 상태를 보고 이름을 붙인다.
const ON_BREAK_ASKS: Partial<Record<StepId, string>> = {
  since: '시간을 갖기 시작한 지 얼마나 되셨습니까',
  initiator: '먼저 시간을 갖자고 한 쪽은 누구입니까',
  selfEndReason: '그때 왜 시간을 갖자고 하셨습니까',
  cling: '시간을 갖자고 한 뒤 붙잡아 보셨습니까',
  partnerActions: '시간을 갖기 시작한 뒤 상대가 먼저 한 일이 있습니까',
};

function textOf(id: StepId, d: IntakeDraft): { ask: string; why: string } {
  const base = TEXT[id];
  if (asksPause(d) && ON_BREAK_ASKS[id]) return { ask: ON_BREAK_ASKS[id] as string, why: base.why };
  if (id === 'since' && d.relationState === 'UNCLEAR') {
    return { ask: '관계가 이렇게 된 지 얼마나 되셨습니까', why: base.why };
  }
  return base;
}

function answered(id: StepId, d: IntakeDraft): boolean {
  switch (id) {
    case 'age':
      return d.userAge !== '' || d.partnerAge !== '';
    case 'gender':
      return d.userGender !== null;
    case 'dating':
      return d.datingYears !== '' || d.datingMonths !== '';
    case 'relation':
      return d.relationState !== null;
    case 'pausePlan':
      return d.pausePlan !== null;
    case 'pauseContact':
      return d.pauseContactRule !== null;
    case 'since':
      return d.sinceValue !== '';
    case 'priorReunion':
      return d.priorReunion !== null;
    case 'repeatPattern':
      return d.repeatBreakupPattern !== null;
    case 'repeatSeverity':
      return d.repeatSeverity !== null;
    case 'priorPath':
      return d.priorReunionPath !== null;
    case 'priorGap':
      return d.priorReunionGap !== null;
    case 'initiator':
      return d.initiator !== null;
    case 'selfEndReason':
      return d.selfEndReason !== null;
    case 'contactMode':
      return d.contactMode !== null;
    case 'lastAction':
      return d.lastActionBeforeCut !== null;
    case 'cling':
      return d.clingReaction !== null;
    case 'preChange':
      return d.preBreakupChange !== null;
    case 'partnerHasNew':
      return d.partnerHasNew !== null;
    case 'overlap':
      return d.newRelationOverlap !== null;
    case 'partnerActions':
      return d.partnerActions.length > 0;
  }
}

export function IntakeWizard({
  step,
  draft,
  onChange,
  onNext,
  onBack,
}: {
  step: number;
  draft: IntakeDraft;
  onChange: (patch: Partial<IntakeDraft>) => void;
  onNext: () => void;
  onBack: () => void;
}) {
  const steps = intakeSteps(draft);
  const id = steps[Math.min(step, steps.length - 1)];
  const filled = answered(id, draft);
  const text = textOf(id, draft);
  // 보기를 고르면 바로 다음으로 넘어간다 — 고르고 또 "다음"을 누르게 하면 탭이 두 배가 된다.
  // 고른 보기가 칠해지는 걸 잠깐은 보여준다. 즉시 넘기면 눌렀는지 모른다.
  // 기타는 글을 받아야 하니 안 넘긴다. 복수 선택도 마찬가지.
  const advanceTimer = useRef<number | null>(null);
  useEffect(() => () => {
    if (advanceTimer.current !== null) window.clearTimeout(advanceTimer.current);
  }, []);
  function pickAndAdvance(patch: Partial<IntakeDraft>, value: string) {
    onChange(patch);
    if (value === 'OTHER' || advanceTimer.current !== null) return;
    advanceTimer.current = window.setTimeout(() => {
      advanceTimer.current = null;
      onNext();
    }, ADVANCE_DELAY);
  }

  function single<K extends keyof IntakeDraft>(key: K, options: Option<string>[]) {
    const current = draft[key] as string | null;
    return (
      <div className={styles.list}>
        {options.map((o) => (
          <button
            key={o.value}
            className={`${styles.listOption} ${
              current === o.value ? styles.listOptionOn : current ? styles.listOptionDim : ''
            }`}
            onClick={() => pickAndAdvance({ [key]: o.value } as Partial<IntakeDraft>, o.value)}
          >
            <span>{o.label}</span>
            {current === o.value && <Check />}
          </button>
        ))}
        {current === 'OTHER' && otherBox(key)}
      </div>
    );
  }

  // 기타를 고르면 바로 아래 글 칸이 열린다. 글은 칸 이름을 키로 따로 들고 있어 보기를 바꿔도
  // 지워지지 않는다(다시 기타로 돌아오면 그대로 있다).
  function otherBox(field: string) {
    return (
      <label className={styles.textBox}>
        <input
          className={styles.text}
          type="text"
          maxLength={200}
          autoFocus
          placeholder="한두 줄로 적어주세요"
          value={draft.otherAnswers[field] ?? ''}
          onChange={(e) => onChange({ otherAnswers: { ...draft.otherAnswers, [field]: e.target.value } })}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.nativeEvent.isComposing) onNext();
          }}
        />
      </label>
    );
  }

  // 하나만 고른다 — 다른 단계처럼 누르면 바로 넘어간다. 복수 선택이던 때는 이 단계만 "다음"을
  // 따로 눌러야 해서 마지막에서 멈칫했다. 저장 형태(배열)는 그대로라 서버는 안 바뀐다.
  function pickPartnerAction(next: PartnerAction) {
    pickAndAdvance({ partnerActions: [next] }, next);
  }

  return (
    <div className={styles.wrap}>
      {/* 진행이 아니라 남은 수를 보여준다. 가지가 열리면 전체 수가 바뀌는데, 분모가 움직이는
          "8 / 14"는 늘어난 게 티가 나고 남은 수는 그냥 하나 덜 줄 뿐이다 */}
      <div className={styles.top}>
        <div className={styles.progress}>
          <div
            className={styles.progressFill}
            style={{ width: `${Math.round((step / steps.length) * 100)}%` }}
          />
        </div>
        <div className={styles.count}>남은 질문 {steps.length - step}개</div>
      </div>
      {/* key로 단계마다 새로 그린다 — 글이 뜨는 움직임이 곧 "누가 말하고 있다"는 느낌이다 */}
      <div className={`${styles.head} ${styles.rise}`} key={`head-${id}`}>
        <div className={styles.section}>
          {SECTION[id] === '이별' && asksPause(draft) ? '멈춤' : SECTION[id]}
        </div>
        <h2 className={styles.ask}>{text.ask}</h2>
        <p className={styles.why}>{text.why}</p>
      </div>

      {/* 단계가 바뀌면 통째로 새로 그린다 — 안 그러면 리액트가 같은 input을 재활용해서
          앞 단계에 준 자동 초점이 다음 단계에서 안 걸린다 */}
      <div className={`${styles.body} ${styles.riseLate}`} key={id}>
        {id === 'relation' && single('relationState', RELATION_STATES)}
        {id === 'pausePlan' && single('pausePlan', PAUSE_PLANS)}
        {id === 'pauseContact' && single('pauseContactRule', PAUSE_CONTACT_RULES)}

        {id === 'age' && (
          <div className={styles.row}>
            <NumberBox
              label="나"
              unit="세"
              autoFocus
              value={draft.userAge}
              onChange={(v) => onChange({ userAge: v })}
            />
            <NumberBox
              label="상대"
              unit="세"
              value={draft.partnerAge}
              onChange={(v) => onChange({ partnerAge: v })}
            />
          </div>
        )}

        {id === 'gender' && (
          <div className={styles.options}>
            {(['MALE', 'FEMALE'] as const).map((g) => (
              <button
                key={g}
                className={`${styles.option} ${draft.userGender === g ? styles.optionOn : ''}`}
                onClick={() => pickAndAdvance({ userGender: g }, g)}
              >
                <span>{g === 'MALE' ? '남성' : '여성'}</span>
                {draft.userGender === g && <Check />}
              </button>
            ))}
          </div>
        )}

        {id === 'dating' && (
          <div className={styles.row}>
            <NumberBox
              unit="년"
              autoFocus
              value={draft.datingYears}
              onChange={(v) => onChange({ datingYears: v })}
            />
            <NumberBox
              unit="개월"
              value={draft.datingMonths}
              onChange={(v) => onChange({ datingMonths: v })}
            />
          </div>
        )}

        {id === 'since' && (
          <div className={styles.sinceRow}>
            <label className={styles.numberBox}>
              <input
                className={styles.number}
                type="number"
                inputMode="numeric"
                autoFocus
                value={draft.sinceValue}
                onChange={(e) => onChange({ sinceValue: e.target.value })}
              />
            </label>
            {/* 단위는 성별 보기와 같은 문법 — 글자만 늘어놓고 고른 것만 색이 든다.
                기본은 개월: 며칠 안이나 몇 년은 한 번 더 눌러 바꾼다 */}
            <div className={styles.unitToggle}>
              {SINCE_UNITS.map((u) => (
                <button
                  key={u.value}
                  className={`${styles.unitOption} ${draft.sinceUnit === u.value ? styles.optionOn : ''}`}
                  onClick={() => onChange({ sinceUnit: u.value })}
                >
                  {u.label}
                </button>
              ))}
            </div>
          </div>
        )}

        {id === 'priorReunion' && single('priorReunion', PRIOR_REUNIONS)}
        {id === 'repeatPattern' && single('repeatBreakupPattern', REPEAT_PATTERNS)}
        {id === 'repeatSeverity' && single('repeatSeverity', REPEAT_SEVERITIES)}
        {id === 'priorPath' && single('priorReunionPath', PRIOR_PATHS)}
        {id === 'priorGap' && single('priorReunionGap', PRIOR_GAPS)}
        {id === 'initiator' && single('initiator', INITIATORS)}
        {id === 'selfEndReason' && single('selfEndReason', SELF_END_REASONS)}
        {id === 'contactMode' && single('contactMode', CONTACT_MODES)}
        {id === 'lastAction' && single('lastActionBeforeCut', LAST_ACTIONS)}
        {id === 'cling' && single('clingReaction', CLING_REACTIONS)}
        {id === 'preChange' && single('preBreakupChange', PRE_BREAKUP_CHANGES)}
        {id === 'partnerHasNew' && single('partnerHasNew', PARTNER_NEW)}
        {id === 'overlap' && single('newRelationOverlap', OVERLAPS)}

        {id === 'partnerActions' && (
          <div className={styles.list}>
            {PARTNER_ACTIONS.map((o) => (
              <button
                key={o.value}
                className={`${styles.listOption} ${
                  draft.partnerActions.includes(o.value) ? styles.listOptionOn : ''
                }`}
                onClick={() => pickPartnerAction(o.value)}
              >
                <span>{o.label}</span>
                {draft.partnerActions.includes(o.value) && <Check />}
              </button>
            ))}
            {draft.partnerActions.includes('OTHER') && otherBox('partnerActions')}
          </div>
        )}
      </div>

      <div className={styles.foot}>
        {/* 버튼을 둘로 나누지 않는다. 안 채운 채로 누르는 것과 건너뛰는 것이 어차피 같은
            일이라, 따로 세우면 유저가 둘의 차이를 찾느라 멈춘다. 라벨만 바뀐다 */}
        {step > 0 ? (
          <button className={styles.back} onClick={onBack}>
            이전
          </button>
        ) : (
          <span className={styles.footSpacer} />
        )}
        <button
          className={`${styles.next} ${filled ? styles.nextReady : ''}`}
          onClick={onNext}
        >
          {filled ? '다음' : '건너뛰기'}
        </button>
      </div>
    </div>
  );
}

function NumberBox({
  label,
  unit,
  value,
  autoFocus,
  onChange,
}: {
  label?: string;
  unit: string;
  value: string;
  autoFocus?: boolean;
  onChange: (value: string) => void;
}) {
  return (
    <label className={styles.numberBox}>
      {label && <span className={styles.numberLabel}>{label}</span>}
      <input
        className={styles.number}
        type="number"
        inputMode="numeric"
        autoFocus={autoFocus}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      />
      <span className={styles.unit}>{unit}</span>
    </label>
  );
}
