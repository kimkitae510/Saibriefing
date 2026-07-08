import { api } from './client';

export interface StoryResponse {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
  unread: boolean; // 마지막으로 읽은 뒤 새 답이 있음 — 목록 배지용
  lastMessage: string | null; // 마지막 메시지 미리보기(한 줄, 서버에서 잘림). 대화 없으면 null
}

export async function listStories(): Promise<StoryResponse[]> {
  const { data } = await api.get<StoryResponse[]>('/api/stories');
  return data;
}

export async function createStory(title?: string): Promise<StoryResponse> {
  const { data } = await api.post<StoryResponse>('/api/stories', title ? { title } : {});
  return data;
}

export async function deleteStory(storyId: number): Promise<void> {
  await api.delete(`/api/stories/${storyId}`);
}

export type MessageRole = 'USER' | 'ASSISTANT';

export interface MessageResponse {
  id: number;
  role: MessageRole;
  content: string;
  createdAt: string;
  // 답을 못 받아 폴백이 저장된 턴. 화면은 이 값으로 재시도 버튼을 띄운다
  // (폴백 문구를 프론트가 복사해 비교하면 문구를 고칠 때마다 두 곳이 어긋난다)
  failed: boolean;
  // 탐색 목표의 진행(채워진 수 / 전체). 상담자 답에만 붙고, 목표가 꺼져 있으면 없다.
  // 둘이 같아지는 순간이 리포트 입구를 세우는 자리다.
  goalsDone?: number | null;
  goalsTotal?: number | null;
}

export interface MessagePageResponse {
  messages: MessageResponse[];
  nextCursor: number | null;
  hasNext: boolean;
}

// 과거→현재 순으로 최근 size개. cursor보다 과거를 이어서 로드(위로 스크롤).
export async function getMessages(
  storyId: number,
  cursor?: number,
  size = 30,
): Promise<MessagePageResponse> {
  const { data } = await api.get<MessagePageResponse>(`/api/stories/${storyId}/messages`, {
    params: { cursor, size },
  });
  return data;
}

// 폴링 방식: 유저 메시지만 저장하고 즉시 반환(202). 어시스턴트 답은 이후 since로 받아온다.
export async function sendMessage(storyId: number, content: string): Promise<MessageResponse> {
  const { data } = await api.post<MessageResponse>(`/api/stories/${storyId}/messages`, { content });
  return data;
}

// 답을 못 받은 턴의 재시도. 보낸 말은 그대로 두고 답만 다시 만들므로 본문을 싣지 않는다.
// 서버가 폴백 말풍선을 지우므로 폴링 기준 id를 새로 받아온다(들고 있던 id는 이미 없는 행이다).
export async function retryLastReply(storyId: number): Promise<{ pollAfterId: number }> {
  const { data } = await api.post<{ pollAfterId: number }>(
    `/api/stories/${storyId}/messages/retry`,
  );
  return data;
}

// 분석 화면의 "사실 직접 알려주기" — 채팅 없이 사실 원장에 한 줄 쌓고 재분석 가드를 통과시킨다.
// 반환된 id로 취소/수정을 건다.
export async function addStoryFact(storyId: number, content: string): Promise<number> {
  const { data } = await api.post<{ id: number }>(`/api/stories/${storyId}/facts`, { content });
  return data.id;
}

// 직접 적은 사실의 취소. 유저가 입력한 줄만 지워진다(추출된 사실은 대상이 아님).
export async function deleteStoryFact(storyId: number, factId: number): Promise<void> {
  await api.delete(`/api/stories/${storyId}/facts/${factId}`);
}

// afterId 이후 새로 생긴 메시지(주로 어시스턴트 답)를 시간순으로.
export async function getMessagesSince(storyId: number, afterId: number): Promise<MessageResponse[]> {
  const { data } = await api.get<MessageResponse[]>(`/api/stories/${storyId}/messages/since`, {
    params: { after: afterId },
  });
  return data;
}
