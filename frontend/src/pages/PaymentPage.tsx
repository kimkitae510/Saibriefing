import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { PhoneFrame } from '../components/PhoneFrame';
import { BusinessInfo } from '../components/BusinessInfo';
import {
  createOrder,
  confirmPayment,
  getPayment,
  getPaymentConfig,
  listPayments,
  type OrderCreateResponse,
  type PaymentConfig,
  type PaymentResponse,
} from '../api/payment';
import { getUsage, type UsageStatusResponse } from '../api/usage';
import { extractErrorMessage } from '../api/client';
import { formatListTime } from '../utils/datetime';
import { paymentOrigin } from '../utils/paymentOrigin';
import styles from './PaymentPage.module.css';

const STATUS_LABEL: Record<string, string> = {
  READY: '결제 대기',
  IN_PROGRESS: '확인 중',
  WAITING_FOR_DEPOSIT: '입금 대기',
  DONE: '결제 완료',
  FAILED: '실패',
  EXPIRED: '만료',
  CANCEL_REQUESTED: '환불 처리 중',
  CANCELED: '환불 완료',
};

// MATCH(비슷한 사례)는 분석 리포트에 들어가는 내용이라 목록에 세지 않는다 —
// 라벨이 없으면 enum 이름이 화면에 그대로 새어 나온다(실측: "분석 1회 + MATCH 1회").
const KIND_LABEL: Record<string, string> = { CHAT: '대화', ASSESSMENT: '분석' };
const HIDDEN_KINDS = new Set(['MATCH']);

// 토스 SDK는 외부 스크립트라 필요할 때(실결제 모드) 한 번만 끼워 넣는다.
function loadTossSdk(): Promise<any> {
  return new Promise((resolve, reject) => {
    const w = window as any;
    if (w.TossPayments) return resolve(w.TossPayments);
    const script = document.createElement('script');
    script.src = 'https://js.tosspayments.com/v2/standard';
    script.onload = () => resolve((window as any).TossPayments);
    script.onerror = () => reject(new Error('결제 모듈을 불러오지 못했습니다.'));
    document.head.appendChild(script);
  });
}

// 패들 SDK도 같은 방식으로 필요할 때만 끼워 넣는다.
function loadPaddleSdk(): Promise<any> {
  return new Promise((resolve, reject) => {
    const w = window as any;
    if (w.Paddle) return resolve(w.Paddle);
    const script = document.createElement('script');
    script.src = 'https://cdn.paddle.com/paddle/v2/paddle.js';
    script.onload = () => resolve((window as any).Paddle);
    script.onerror = () => reject(new Error('결제 모듈을 불러오지 못했습니다.'));
    document.head.appendChild(script);
  });
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// 토스 위젯이 요구하는 구매자 식별자. 브라우저별 랜덤 발급으로 충분하다(회원 정보 노출 없음).
function customerKey(): string {
  const saved = localStorage.getItem('toss-customer-key');
  if (saved) return saved;
  const fresh = crypto.randomUUID();
  localStorage.setItem('toss-customer-key', fresh);
  return fresh;
}

export function PaymentPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [config, setConfig] = useState<PaymentConfig | null>(null);
  const [usage, setUsage] = useState<UsageStatusResponse | null>(null);
  const [payments, setPayments] = useState<PaymentResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [buying, setBuying] = useState(false);
  // 청약철회 제한 고지 동의 — 전자상거래법상 결제 전에 받아야 한다.
  // 상시 체크박스 대신 가격 버튼을 누른 순간 시트로 물어본다(동의하고 결제 = 명시적 동의).
  const [consentItem, setConsentItem] = useState<string | null>(null);
  // 실결제(toss) 모드에서만: 주문을 만들고 위젯을 펼친 상태
  const [widgetOrder, setWidgetOrder] = useState<OrderCreateResponse | null>(null);
  const [widgetReady, setWidgetReady] = useState(false);
  const widgetsRef = useRef<any>(null);
  const aliveRef = useRef(true);
  // 패들은 결제창이 닫힌 뒤 결과가 잠깐 뒤에 확정된다 — 그 사이를 덮는 표시.
  const [settling, setSettling] = useState(false);
  // 패들 초기화는 한 번뿐이라 콜백도 그때 고정된다. 진행 중인 주문은 ref로 넘긴다.
  const paddleInitRef = useRef(false);
  const pendingOrderRef = useRef<OrderCreateResponse | null>(null);

  useEffect(() => {
    if (!error) return;
    const timer = window.setTimeout(() => aliveRef.current && setError(''), 6000);
    return () => clearTimeout(timer);
  }, [error]);

  // 충전 완료 알림도 에러와 같은 수명 — 계속 떠 있으면 잔여 표시와 정보가 겹쳐 소음이 된다.
  useEffect(() => {
    if (!notice) return;
    const timer = window.setTimeout(() => aliveRef.current && setNotice(''), 6000);
    return () => clearTimeout(timer);
  }, [notice]);

  const refresh = useCallback(() => {
    getUsage().then((u) => aliveRef.current && setUsage(u)).catch(() => {});
    listPayments().then((p) => aliveRef.current && setPayments(p)).catch(() => {});
  }, []);

  useEffect(() => {
    aliveRef.current = true;
    getPaymentConfig()
      .then((c) => aliveRef.current && setConfig(c))
      .catch((e) => aliveRef.current && setError(extractErrorMessage(e, '상품 정보를 불러오지 못했습니다.')))
      .finally(() => aliveRef.current && setLoading(false));
    refresh();
    return () => {
      aliveRef.current = false;
    };
  }, [refresh]);

  // 주문이 만들어지면 위젯을 그 자리에서 렌더링한다(결제수단 선택 + 약관).
  useEffect(() => {
    if (!widgetOrder || !config?.clientKey) return;
    let alive = true;
    (async () => {
      try {
        const TossPayments = await loadTossSdk();
        const widgets = TossPayments(config.clientKey).widgets({ customerKey: customerKey() });
        await widgets.setAmount({ currency: 'KRW', value: widgetOrder.amount });
        await Promise.all([
          widgets.renderPaymentMethods({ selector: '#pay-methods' }),
          widgets.renderAgreement({ selector: '#pay-agreement' }),
        ]);
        if (alive) {
          widgetsRef.current = widgets;
          setWidgetReady(true);
        }
      } catch (e) {
        if (alive) {
          setWidgetOrder(null);
          setError(e instanceof Error ? e.message : '결제 화면을 열지 못했습니다.');
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, [widgetOrder, config]);

  // 패들은 결제창에서 결제가 이미 끝난 뒤에 우리가 확인한다. 승인 호출로 즉시 확정을
  // 시도하되, 패들이 아직 처리 중이면 웹훅이 확정할 때까지 잠깐 폴링해 결과를 기다린다.
  const settle = useCallback(
    async (order: OrderCreateResponse) => {
      setSettling(true);
      try {
        let result: PaymentResponse | null = null;
        try {
          result = await confirmPayment({
            paymentKey: order.pgRef ?? '',
            orderId: order.orderId,
            amount: order.amount,
          });
        } catch {
          // 웹훅이 먼저 도착해 이미 처리 중이거나 끝난 경우 — 아래 폴링이 결론을 가져온다.
        }
        for (let i = 0; i < 12 && result?.status !== 'DONE'; i += 1) {
          if (!aliveRef.current) return;
          await sleep(1500);
          result = await getPayment(order.orderId);
        }
        if (!aliveRef.current) return;
        if (result?.status === 'DONE') {
          setNotice(`충전 완료 — ${result.itemName}`);
        } else {
          setError('결제 확인이 지연되고 있습니다. 잠시 후 구매 내역에서 확인해 주세요.');
        }
        refresh();
      } finally {
        if (aliveRef.current) setSettling(false);
      }
    },
    [refresh],
  );

  // 버튼을 누른 뒤에 SDK를 불러오면 초기화가 끝나기 전에 결제창을 열게 돼 첫 시도가
  // 조용히 실패한다(두 번째부터는 이미 로드돼 있어 되는 탓에 재현이 들쭉날쭉했다).
  // 화면에 들어온 시점에 미리 불러 초기화까지 끝내 둔다 — 결제창도 그만큼 빨리 열린다.
  useEffect(() => {
    if (config?.provider !== 'paddle' || !config.clientKey || paddleInitRef.current) return;
    let alive = true;
    loadPaddleSdk()
      .then((Paddle) => {
        if (!alive || paddleInitRef.current) return;
        // 환경을 별도 설정으로 두면 라이브 전환 때 잊기 딱 좋고, 잊으면 결제가 전부 죽는다.
        // 토큰 접두어(test_/live_)가 이미 환경을 말해주므로 그걸 따른다 — 키만 갈면 끝.
        Paddle.Environment.set(config.clientKey.startsWith('test_') ? 'sandbox' : 'production');
        Paddle.Initialize({
          token: config.clientKey,
          eventCallback: (event: any) => {
            if (event?.name !== 'checkout.completed') return;
            const pending = pendingOrderRef.current;
            pendingOrderRef.current = null;
            if (pending) void settle(pending);
          },
        });
        paddleInitRef.current = true;
      })
      .catch(() => {
        if (alive) setError('결제 모듈을 불러오지 못했습니다. 새로고침 후 다시 시도해 주세요.');
      });
    return () => {
      alive = false;
    };
  }, [config, settle]);

  async function openPaddleCheckout(order: OrderCreateResponse) {
    // 화면 진입 직후에 눌렀다면 초기화가 아직 안 끝났을 수 있다 — 잠깐 기다렸다 연다.
    for (let i = 0; i < 20 && !paddleInitRef.current; i += 1) {
      await sleep(150);
    }
    if (!paddleInitRef.current) {
      throw new Error('결제 모듈이 아직 준비되지 않았습니다. 잠시 후 다시 시도해 주세요.');
    }
    pendingOrderRef.current = order;
    // 주문 생성 때 서버가 만들어 둔 거래를 그대로 연다 — 금액과 상품이 서버에 고정돼 있어
    // 결제창에서 바꿀 수 없다.
    (window as any).Paddle.Checkout.open({ transactionId: order.pgRef });
  }

  async function buy(itemCode: string) {
    if (buying || !config) return;
    setBuying(true);
    setError('');
    setNotice('');
    try {
      // 시트의 "동의하고 결제"를 거쳐서만 호출된다 — 동의는 항상 참
      const order = await createOrder(itemCode, true);
      if (config.provider === 'paddle') {
        await openPaddleCheckout(order);
      } else if (!config.clientKey) {
        // mock 모드: PG 없이 서버 mock 게이트웨이가 즉시 승인한다 — 키 없이 전체 흐름 확인용.
        const done = await confirmPayment({
          paymentKey: `mock-${order.orderId}`,
          orderId: order.orderId,
          amount: order.amount,
        });
        if (aliveRef.current) {
          setNotice(`충전 완료 — ${done.itemName}`);
          refresh();
        }
      } else {
        setWidgetReady(false);
        setWidgetOrder(order);
      }
    } catch (e) {
      if (aliveRef.current) setError(extractErrorMessage(e, '구매를 시작하지 못했습니다.'));
    } finally {
      if (aliveRef.current) setBuying(false);
    }
  }

  // successUrl/failUrl로 리다이렉트되므로 이 함수는 정상 흐름에선 돌아오지 않는다.
  async function payWithWidget() {
    if (!widgetOrder || !widgetsRef.current) return;
    try {
      await widgetsRef.current.requestPayment({
        orderId: widgetOrder.orderId,
        orderName: widgetOrder.orderName,
        successUrl: `${window.location.origin}/payment/success`,
        failUrl: `${window.location.origin}/payment/fail`,
      });
    } catch (e) {
      // 유저가 결제창을 닫은 경우 등 — 주문(READY)은 서버가 30분 뒤 알아서 만료시킨다.
      setError(e instanceof Error && e.message ? e.message : '결제가 진행되지 않았습니다.');
    }
  }

  function grantsText(p: { grants: { kind: string; count: number }[] }): string {
    return p.grants
      .filter((g) => !HIDDEN_KINDS.has(g.kind))
      .map((g) => `${KIND_LABEL[g.kind] ?? g.kind} ${g.count}회`)
      .join(' + ');
  }

  // 이용권은 대화방, 분석, 서랍 등 여러 자리에서 들어온다. '/stories'로 돌려보내면 통로가
  // 가장 최근 방을 열어, 들어온 방과 다른 방에 떨어진다. 온 길 그대로 되짚는다.
  // 히스토리가 없는 첫 화면(새로고침, 링크 직접 진입)이면 적어둔 출발 자리로 보낸다.
  function goBack() {
    if (location.key === 'default') {
      navigate(paymentOrigin());
      return;
    }
    navigate(-1);
  }

  return (
    <PhoneFrame>
      <div className={styles.wrap}>
        <div className={styles.topbar}>
          <button className={styles.backButton} onClick={goBack} aria-label="뒤로">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none">
              <path d="M15 5l-7 7 7 7" stroke="#ebebee" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className={styles.topTitle}>이용권</div>
          <div className={styles.topSpacer} />
        </div>

        {error && (
          <div className={styles.errorBanner}>
            <svg className={styles.noticeIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="#D88B9F" strokeWidth="1.6" />
              <path d="M12 8v5M12 15.8h.01" stroke="#D88B9F" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            {error}
          </div>
        )}
        {notice && (
          <div className={styles.noticeBanner}>
            <svg className={styles.noticeIcon} width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="12" r="9" stroke="#B89DD1" strokeWidth="1.6" />
              <path d="M8.5 12.2l2.4 2.4 4.6-5" stroke="#B89DD1" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            {notice}
          </div>
        )}

        {settling && <div className={styles.state}>결제를 확인하는 중…</div>}

        {loading ? (
          <div className={styles.state}>불러오는 중…</div>
        ) : (
          <div className={styles.body}>
            {usage && (
              <div className={styles.balanceCard}>
                <div className={styles.balanceRow}>
                  <span className={styles.balanceKey}>대화</span>
                  <span className={styles.balanceValue}>{usage.chatRemaining}회 남음</span>
                </div>
                <div className={styles.balanceRow}>
                  <span className={styles.balanceKey}>분석</span>
                  <span className={styles.balanceValue}>{usage.assessmentRemaining}회 남음</span>
                </div>
                <div className={styles.balanceHint}>충전한 횟수는 기간 제한 없이 남아 있습니다.</div>
              </div>
            )}

            {config && !widgetOrder && (
              <>
                <div className={styles.sectionTitle}>이용권 구매</div>
                {config.items.map((item) => (
                  <div className={styles.itemCard} key={item.code}>
                    <div className={styles.itemInfo}>
                      <div className={styles.itemName}>{item.name}</div>
                      <div className={styles.itemGrants}>{grantsText(item)} / 기한 없음</div>
                    </div>
                    {/* PG 심사 전에는 상품과 가격만 보여주고 결제는 막는다 —
                        심사자가 무엇을 얼마에 파는지는 확인할 수 있어야 한다 */}
                    <button
                      className={styles.buyButton}
                      onClick={() => setConsentItem(item.code)}
                      disabled={buying || config.provider === 'disabled'}
                    >
                      {config.provider === 'disabled'
                        ? '준비 중'
                        : buying
                          ? '진행 중…'
                          : `${item.amount.toLocaleString()}원`}
                    </button>
                  </div>
                ))}
              </>
            )}

            {widgetOrder && (
              <div className={styles.widgetCard}>
                <div className={styles.itemName}>{widgetOrder.orderName}</div>
                <div id="pay-methods" />
                <div id="pay-agreement" />
                <button className={styles.btnPrimary} onClick={payWithWidget} disabled={!widgetReady}>
                  {widgetReady ? `${widgetOrder.amount.toLocaleString()}원 결제하기` : '결제 수단 불러오는 중…'}
                </button>
                <button className={styles.btnGhost} onClick={() => setWidgetOrder(null)}>
                  취소
                </button>
              </div>
            )}

            <div className={styles.sectionTitle}>구매 내역</div>
            {payments.length === 0 ? (
              <div className={styles.emptyHistory}>아직 구매 내역이 없습니다.</div>
            ) : (
              payments.map((p) => (
                <div className={styles.historyCard} key={p.orderId}>
                  <div className={styles.historyTop}>
                    <span className={styles.historyName}>{p.itemName}</span>
                    <span className={`${styles.statusBadge} ${styles[`st${p.status}`] ?? ''}`}>
                      {STATUS_LABEL[p.status] ?? p.status}
                    </span>
                  </div>
                  <div className={styles.historyMeta}>
                    {formatListTime(p.createdAt)}, {p.amount.toLocaleString()}원
                    {p.method ? ` (${p.method})` : ''}
                    {p.status === 'CANCELED' && ` / 환불 ${p.canceledAmount.toLocaleString()}원`}
                  </div>
                  {/* 구매별 사용량(N/M회 사용)은 뺐다 — 잔여는 위 카드가 말하고,
                      영수증에 소모 현황까지 얹으면 정보가 겹쳐 읽기만 어려워진다 */}
                  {p.status === 'WAITING_FOR_DEPOSIT' && p.vbankAccount && (
                    <div className={styles.vbankBox}>
                      입금 계좌: {p.vbankBank} {p.vbankAccount}
                      {p.vbankDueAt && (
                        <>
                          <br />
                          {formatListTime(p.vbankDueAt)}까지 입금해 주세요.
                        </>
                      )}
                    </div>
                  )}
                  {p.status === 'FAILED' && p.failReason && (
                    <div className={styles.failReason}>{p.failReason}</div>
                  )}
                  {/* 환불 버튼은 두지 않는다 — 미사용 전액 환불(약관)은 1:1 문의로 접수한다 */}
                </div>
              ))
            )}

            {/* 돈을 내기 직전에 판매자가 누구인지 보여야 한다 */}
            <BusinessInfo />
          </div>
        )}

        {consentItem && (
          <div className={styles.sheetOverlay} onClick={() => setConsentItem(null)}>
            <div className={styles.sheet} onClick={(e) => e.stopPropagation()}>
              <div className={styles.sheetTitle}>결제 전에 확인해 주세요</div>
              <div className={styles.sheetText}>
                이용권은 결제 즉시 지급되는 디지털 콘텐츠로, 사용을 시작하면 환불(청약철회)이
                제한됩니다. 사용하지 않은 이용권은 1:1 문의로 전액 환불받을 수 있습니다.
              </div>
              <button
                className={styles.btnPrimary}
                onClick={() => {
                  const code = consentItem;
                  setConsentItem(null);
                  void buy(code);
                }}
              >
                동의하고 결제하기
              </button>
              <button className={styles.sheetCancel} onClick={() => setConsentItem(null)}>
                취소
              </button>
            </div>
          </div>
        )}
      </div>
    </PhoneFrame>
  );
}
