import { api } from './client';

// 첫 대화 전에 한 번 받는 기본 정보. 값은 서버 enum의 이름 그대로 주고받는다 —
// 화면에 보이는 한글 문구가 바뀌어도 저장값과 매칭 사전은 흔들리지 않게 한다.
export type IntakeGender = 'MALE' | 'FEMALE';
// 상황을 고르는 칸은 전부 "잘 모르겠다"와 "기타"를 보기로 둔다. 기타를 고르면 직접 쓴 글이
// otherAnswers에 칸 이름을 키로 실린다.
export type BreakupInitiator = 'PARTNER' | 'SELF' | 'PUSHED' | 'UNKNOWN' | 'OTHER';
export type ContactMode =
  | 'PARTNER_REACHES'
  | 'MUTUAL'
  | 'I_INITIATE'
  | 'READ_NO_REPLY'
  | 'NONE'
  | 'BLOCKED'
  | 'UNKNOWN'
  | 'OTHER';
export type PriorReunion = 'NONE' | 'ONCE' | 'MANY' | 'UNSURE' | 'OTHER';
export type PartnerNewRelation = 'CONFIRMED' | 'DENIED' | 'UNKNOWN' | 'OTHER';
export type PreBreakupChange =
  | 'SUDDEN'
  | 'FIGHTS_INCREASED'
  | 'GRADUAL_COOLING'
  | 'REPEATED_WARNINGS'
  | 'SINGLE_INCIDENT'
  | 'UNSURE'
  | 'OTHER';
export type PartnerAction =
  | 'REACHED_OUT'
  | 'ASKED_TO_MEET'
  | 'SNS_TRACE'
  | 'BELONGINGS_ONLY'
  | 'CUT_OFF'
  | 'NOTHING'
  | 'UNKNOWN'
  | 'OTHER';
export type ContactPoint =
  | 'NONE'
  | 'SCHOOL_OR_WORK'
  | 'NEIGHBORHOOD'
  | 'MUTUAL_FRIENDS'
  | 'SCHEDULED'
  | 'UNSETTLED';
export type ClingReaction = 'CLUNG_FIRM' | 'CLUNG_WAVERED' | 'LITTLE' | 'NONE' | 'UNSURE' | 'OTHER';
// 아래 셋은 앞 답에 따라 묻는 가지다 — 조건이 안 맞으면 화면이 안 물으니 null이 정상이다.
export type RepeatBreakupPattern = 'SAME_ISSUE' | 'DIFFERENT' | 'UNSURE' | 'OTHER';
export type RepeatSeverity = 'MORE_FINAL' | 'SIMILAR' | 'LIGHTER' | 'UNSURE' | 'OTHER';
export type PriorReunionPath = 'PARTNER_RETURNED' | 'I_HELD_ON' | 'DRIFTED_BACK' | 'UNSURE' | 'OTHER';
export type LastActionBeforeCut = 'BEGGED' | 'ANGRY' | 'NORMAL' | 'UNKNOWN' | 'OTHER';
export type SelfEndReason = 'IMPULSIVE' | 'WORN_OUT' | 'EXTERNAL' | 'TEST' | 'UNSURE' | 'OTHER';
export type NewRelationOverlap = 'DURING' | 'RIGHT_AFTER' | 'LATER' | 'UNSURE' | 'OTHER';

export interface StoryIntake {
  // 상담자가 대화에서 부르는 이름. 다른 칸처럼 비워둘 수 있고, 빈 문자열이 곧 안 적음이다.
  callName: string;
  userAge: number | null;
  partnerAge: number | null;
  userGender: IntakeGender | null;
  datingMonths: number | null;
  daysSinceBreakup: number | null;
  initiator: BreakupInitiator | null;
  contactMode: ContactMode | null;
  priorReunion: PriorReunion | null;
  partnerHasNew: PartnerNewRelation | null;
  preBreakupChange: PreBreakupChange | null;
  clingReaction: ClingReaction | null;
  repeatBreakupPattern: RepeatBreakupPattern | null;
  repeatSeverity: RepeatSeverity | null;
  priorReunionPath: PriorReunionPath | null;
  lastActionBeforeCut: LastActionBeforeCut | null;
  selfEndReason: SelfEndReason | null;
  newRelationOverlap: NewRelationOverlap | null;
  partnerActions: PartnerAction[];
  contactPoints: ContactPoint[];
  otherAnswers: Record<string, string>;
}

export interface StoryIntakeResponse extends StoryIntake {
  // false면 아직 안 낸 사연 — 이때만 폼을 띄운다. 나머지 값은 직전 사연에서 물려받은 미리 채움.
  submitted: boolean;
}

export const EMPTY_INTAKE: StoryIntake = {
  callName: '',
  userAge: null,
  partnerAge: null,
  userGender: null,
  datingMonths: null,
  daysSinceBreakup: null,
  initiator: null,
  contactMode: null,
  priorReunion: null,
  partnerHasNew: null,
  preBreakupChange: null,
  clingReaction: null,
  repeatBreakupPattern: null,
  repeatSeverity: null,
  priorReunionPath: null,
  lastActionBeforeCut: null,
  selfEndReason: null,
  newRelationOverlap: null,
  partnerActions: [],
  contactPoints: [],
  otherAnswers: {},
};

export async function getIntake(storyId: number): Promise<StoryIntakeResponse> {
  const { data } = await api.get<StoryIntakeResponse>(`/api/stories/${storyId}/intake`);
  return data;
}

// 부분 수정이 아니라 통째 교체다 — 건너뛴 칸과 지운 칸을 구분할 수 없어 늘 전체를 보낸다.
export async function putIntake(storyId: number, intake: StoryIntake): Promise<StoryIntakeResponse> {
  const { data } = await api.put<StoryIntakeResponse>(`/api/stories/${storyId}/intake`, intake);
  return data;
}
