import type {
  BreakupInitiator,
  ClingReaction,
  ContactMode,
  IntakeGender,
  LastActionBeforeCut,
  NewRelationOverlap,
  PartnerAction,
  PartnerNewRelation,
  PreBreakupChange,
  PriorReunion,
  PriorReunionPath,
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
  | 'name'
  | 'age'
  | 'gender'
  | 'dating'
  | 'since'
  | 'priorReunion'
  | 'repeatPattern'
  | 'repeatSeverity'
  | 'priorPath'
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
  callName: string;
  userAge: string;
  partnerAge: string;
  userGender: IntakeGender | null;
  datingYears: string;
  datingMonths: string;
  sinceMonths: string;
  sinceDays: string;
  priorReunion: PriorReunion | null;
  repeatBreakupPattern: RepeatBreakupPattern | null;
  repeatSeverity: RepeatSeverity | null;
  priorReunionPath: PriorReunionPath | null;
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
  callName: '',
  userAge: '',
  partnerAge: '',
  userGender: null,
  datingYears: '',
  datingMonths: '',
  sinceMonths: '',
  sinceDays: '',
  priorReunion: null,
  repeatBreakupPattern: null,
  repeatSeverity: null,
  priorReunionPath: null,
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

// 답에 따라 단계가 늘고 준다. 가지는 조건이 된 질문 바로 뒤에 끼운다 — 뒤로 몰면
// 무엇에 대한 질문인지 잊는다.
export function intakeSteps(draft: IntakeDraft): StepId[] {
  const steps: StepId[] = ['name', 'age', 'gender', 'dating', 'since', 'priorReunion'];
  if (asksRepeatPattern(draft)) steps.push('repeatPattern', 'repeatSeverity', 'priorPath');
  steps.push('initiator');
  if (asksSelfEndReason(draft)) steps.push('selfEndReason');
  steps.push('contactMode');
  if (asksLastAction(draft)) steps.push('lastAction');
  steps.push('cling', 'preChange', 'partnerHasNew');
  if (asksOverlap(draft)) steps.push('overlap');
  steps.push('partnerActions');
  return steps;
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
    callName: draft.callName.trim(),
    userAge: draft.userAge === '' ? null : Number(draft.userAge),
    partnerAge: draft.partnerAge === '' ? null : Number(draft.partnerAge),
    userGender: draft.userGender,
    datingMonths: merge(draft.datingYears, draft.datingMonths, 12),
    daysSinceBreakup: merge(draft.sinceMonths, draft.sinceDays, 30),
    priorReunion: draft.priorReunion,
    repeatBreakupPattern: asksRepeatPattern(draft) ? draft.repeatBreakupPattern : null,
    repeatSeverity: asksRepeatPattern(draft) ? draft.repeatSeverity : null,
    priorReunionPath: asksRepeatPattern(draft) ? draft.priorReunionPath : null,
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
    priorReunion: draft.priorReunion,
    repeatBreakupPattern: asksRepeatPattern(draft) ? draft.repeatBreakupPattern : null,
    repeatSeverity: asksRepeatPattern(draft) ? draft.repeatSeverity : null,
    priorReunionPath: asksRepeatPattern(draft) ? draft.priorReunionPath : null,
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
// 혼자만 설 수 있는 보기. 서버도 한 번 더 접는다.
const EXCLUSIVE_ACTIONS: PartnerAction[] = ['NOTHING', 'UNKNOWN'];

// 질문과 그 이유. 이유는 유저에게 하는 말이라 무엇을 계산하는지가 아니라 왜 답이 판을
// 가르는지를 적는다.
const ADVANCE_DELAY = 180;

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
  const since = span(draft.sinceMonths, draft.sinceDays, '개월', '일');
  const brk = join([
    since && since + ' 전',
    other('initiator', draft.initiator, label(INITIATORS, draft.initiator)),
    asksSelfEndReason(draft) ? other('selfEndReason', draft.selfEndReason, label(SELF_END_REASONS, draft.selfEndReason)) : '',
    other('preBreakupChange', draft.preBreakupChange, label(PRE_BREAKUP_CHANGES, draft.preBreakupChange)),
  ]);
  if (brk) rows.push(['이별', brk]);
  const rep = join([
    other('priorReunion', draft.priorReunion, label(PRIOR_REUNIONS, draft.priorReunion)),
    asksRepeatPattern(draft) ? other('repeatBreakupPattern', draft.repeatBreakupPattern, label(REPEAT_PATTERNS, draft.repeatBreakupPattern)) : '',
    asksRepeatPattern(draft) ? other('repeatSeverity', draft.repeatSeverity, label(REPEAT_SEVERITIES, draft.repeatSeverity)) : '',
    asksRepeatPattern(draft) ? other('priorReunionPath', draft.priorReunionPath, label(PRIOR_PATHS, draft.priorReunionPath)) : '',
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

const TEXT: Record<StepId, { ask: string; why: string }> = {
  name: {
    ask: '뭐라고 불러드리면 되겠습니까',
    why: '대화에서 부를 이름입니다. 실명이 아니어도 됩니다.',
  },
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
    why: '지금 연락이 없어도 한 번 먼저 온 판은 다릅니다. 여러 개 고를 수 있습니다.',
  },
};

function answered(id: StepId, d: IntakeDraft): boolean {
  switch (id) {
    case 'name':
      return d.callName.trim() !== '';
    case 'age':
      return d.userAge !== '' || d.partnerAge !== '';
    case 'gender':
      return d.userGender !== null;
    case 'dating':
      return d.datingYears !== '' || d.datingMonths !== '';
    case 'since':
      return d.sinceMonths !== '' || d.sinceDays !== '';
    case 'priorReunion':
      return d.priorReunion !== null;
    case 'repeatPattern':
      return d.repeatBreakupPattern !== null;
    case 'repeatSeverity':
      return d.repeatSeverity !== null;
    case 'priorPath':
      return d.priorReunionPath !== null;
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
      <div className={`${styles.list} ${options.length > 5 ? styles.listLeft : ''}`}>
        {options.map((o) => (
          <button
            key={o.value}
            className={`${styles.listOption} ${
              current === o.value ? styles.listOptionOn : current ? styles.listOptionDim : ''
            }`}
            onClick={() => pickAndAdvance({ [key]: o.value } as Partial<IntakeDraft>, o.value)}
          >
            {o.label}
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

  // 배타 보기("아무것도 없었다")는 다른 값과 같이 설 수 없다. 서버도 한 번 더 접지만,
  // 화면에서 안 막으면 유저 눈에 모순된 선택이 그대로 켜져 있다.
  function togglePartnerAction(next: PartnerAction) {
    const list = draft.partnerActions;
    if (list.includes(next)) return onChange({ partnerActions: list.filter((v) => v !== next) });
    if (EXCLUSIVE_ACTIONS.includes(next)) return onChange({ partnerActions: [next] });
    onChange({ partnerActions: [...list.filter((v) => !EXCLUSIVE_ACTIONS.includes(v)), next] });
  }

  return (
    <div className={styles.wrap}>
      {/* 진행이 아니라 남은 수를 보여준다. 가지가 열리면 전체 수가 바뀌는데, 분모가 움직이는
          "8 / 14"는 늘어난 게 티가 나고 남은 수는 그냥 하나 덜 줄 뿐이다 */}
      <div className={styles.count}>남은 질문 {steps.length - step}개</div>
      <div className={styles.mark} />
      <h2 className={styles.ask}>{TEXT[id].ask}</h2>
      <p className={styles.why}>{TEXT[id].why}</p>

      {/* 단계가 바뀌면 통째로 새로 그린다 — 안 그러면 리액트가 같은 input을 재활용해서
          앞 단계에 준 자동 초점이 다음 단계에서 안 걸린다 */}
      <div className={styles.body} key={id}>
        {id === 'name' && (
          <label className={styles.textBox}>
            <input
              className={styles.text}
              type="text"
              maxLength={8}
              autoFocus
              value={draft.callName}
              onChange={(e) => onChange({ callName: e.target.value })}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && !e.nativeEvent.isComposing) onNext();
              }}
            />
          </label>
        )}

        {id === 'age' && (
          <>
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
          </>
        )}

        {id === 'gender' && (
          <div className={styles.options}>
            {(['MALE', 'FEMALE'] as const).map((g) => (
              <button
                key={g}
                className={`${styles.option} ${draft.userGender === g ? styles.optionOn : ''}`}
                onClick={() => pickAndAdvance({ userGender: g }, g)}
              >
                {g === 'MALE' ? '남성' : '여성'}
              </button>
            ))}
          </div>
        )}

        {id === 'dating' && (
          <>
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
          </>
        )}

        {id === 'since' && (
          <>
            <NumberBox
              unit="개월"
              autoFocus
              value={draft.sinceMonths}
              onChange={(v) => onChange({ sinceMonths: v })}
            />
            <NumberBox
              unit="일"
              value={draft.sinceDays}
              onChange={(v) => onChange({ sinceDays: v })}
            />
          </>
        )}

        {id === 'priorReunion' && single('priorReunion', PRIOR_REUNIONS)}
        {id === 'repeatPattern' && single('repeatBreakupPattern', REPEAT_PATTERNS)}
        {id === 'repeatSeverity' && single('repeatSeverity', REPEAT_SEVERITIES)}
        {id === 'priorPath' && single('priorReunionPath', PRIOR_PATHS)}
        {id === 'initiator' && single('initiator', INITIATORS)}
        {id === 'selfEndReason' && single('selfEndReason', SELF_END_REASONS)}
        {id === 'contactMode' && single('contactMode', CONTACT_MODES)}
        {id === 'lastAction' && single('lastActionBeforeCut', LAST_ACTIONS)}
        {id === 'cling' && single('clingReaction', CLING_REACTIONS)}
        {id === 'preChange' && single('preBreakupChange', PRE_BREAKUP_CHANGES)}
        {id === 'partnerHasNew' && single('partnerHasNew', PARTNER_NEW)}
        {id === 'overlap' && single('newRelationOverlap', OVERLAPS)}

        {id === 'partnerActions' && (
          <div className={`${styles.list} ${styles.listLeft}`}>
            {PARTNER_ACTIONS.map((o) => (
              <button
                key={o.value}
                className={`${styles.listOption} ${
                  draft.partnerActions.includes(o.value) ? styles.listOptionOn : ''
                }`}
                onClick={() => togglePartnerAction(o.value)}
              >
                {o.label}
              </button>
            ))}
            {draft.partnerActions.includes('OTHER') && otherBox('partnerActions')}
          </div>
        )}
      </div>

      <div className={styles.foot}>
        {/* 버튼을 둘로 나누지 않는다. 안 채운 채로 누르는 것과 건너뛰는 것이 어차피 같은
            일이라, 따로 세우면 유저가 둘의 차이를 찾느라 멈춘다. 라벨만 바뀐다 */}
        {step > 0 && (
          <button className={styles.back} onClick={onBack}>
            이전
          </button>
        )}
        <button className={`${styles.next} ${filled ? styles.nextReady : ''}`} onClick={onNext}>
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
