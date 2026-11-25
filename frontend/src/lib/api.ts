export type Side = 'BUY' | 'SELL'
export type OrderStatus = 'ACCEPTED' | 'OPEN' | 'PARTIALLY_FILLED' | 'FILLED' | 'CANCELED'

export interface Me { loginId: string; role: 'CUSTOMER' | 'OPERATOR' }
export interface Stock {
  symbol: string; name: string; status: 'TRADABLE' | 'HALTED'
  basePrice: number; lastPrice: number; upperLimit: number; lowerLimit: number
}
export interface Level { price: number; quantity: number }
export interface Book { symbol: string; lastSeq: number; bids: Level[]; asks: Level[] }
export interface Trade { seq: number; price: number; quantity: number; executedAt: string }
export interface Account { accountId: number; cashBalance: number; reservedCash: number; availableCash: number }
export interface Position {
  symbol: string; name: string; quantity: number; sellableQuantity: number; averagePrice: number
  totalCost: number; lastPrice: number; evaluation: number; unrealizedPnl: number; realizedPnl: number
}
export interface Order {
  orderId: number; accountId: number; symbol: string; side: Side; price: number; quantity: number
  filledQuantity: number; status: OrderStatus; cancelRequested: boolean; createdAt: string
}
export interface Execution {
  executionId: number; orderId: number; symbol: string; side: Side; price: number; quantity: number; executedAt: string
}
export interface Ledger {
  ledgerId: number; type: 'DEPOSIT' | 'RESERVE' | 'RELEASE' | 'BUY' | 'SELL'
  cashDelta: number; reservedDelta: number; createdAt: string
}
export interface Page<T> { items: T[]; nextCursor: number | null }
export interface PlaceOrder {
  accountId: number; symbol: string; side: Side; orderType: 'LIMIT'; price: number; quantity: number
}

/** 서버가 거절한 요청. 처리 결과가 확실하다. */
export class ApiError extends Error {
  readonly code: string
  readonly status: number

  constructor(code: string, message: string, status: number) {
    super(message)
    this.code = code
    this.status = status
  }
}

/** 응답을 받지 못했거나 서버 오류라서 처리 여부를 알 수 없는 요청. */
export class UncertainError extends Error {}

/** 서버가 쿠키로 준 CSRF 토큰을 읽는다. */
function csrfToken(): string {
  const value = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='))?.slice('XSRF-TOKEN='.length)
  return value ? decodeURIComponent(value) : ''
}

/** 공통 응답 형식을 벗겨 데이터를 반환한다. 변경 요청에는 CSRF 토큰을 붙인다. */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  let response: Response
  let body: { data: T; error: { code: string; message: string } | null }
  try {
    if (init.method && init.method !== 'GET') {
      // 첫 조회가 끝나기 전에 로그인하면 토큰이 아직 없으므로 먼저 받는다.
      if (!csrfToken()) await fetch('/api/v1/auth/me', { credentials: 'same-origin' })
      headers.set('X-XSRF-TOKEN', csrfToken())
    }
    response = await fetch(`/api/v1${path}`, { ...init, headers, credentials: 'same-origin' })
    body = await response.json()
  } catch {
    throw new UncertainError('서버와 연결하지 못했습니다.')
  }
  if (response.ok) return body.data
  if (response.status >= 500) throw new UncertainError(body.error?.message ?? '서버 오류가 발생했습니다.')
  throw new ApiError(body.error?.code ?? 'UNKNOWN', body.error?.message ?? '요청을 처리하지 못했습니다.', response.status)
}

/** 멱등키와 JSON 본문으로 변경을 요청한다. */
function post<T>(path: string, idempotencyKey: string, body: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(body),
  })
}

export const api = {
  me: () => request<Me>('/auth/me'),
  login: (loginId: string, password: string) =>
    request<Me>('/auth/login', { method: 'POST', body: new URLSearchParams({ loginId, password }) }),
  logout: () => request<null>('/auth/logout', { method: 'POST' }),
  stocks: () => request<Stock[]>('/stocks'),
  trades: (symbol: string) => request<Trade[]>(`/stocks/${encodeURIComponent(symbol)}/trades`),
  book: (symbol: string) => request<Book>(`/orderbooks/${encodeURIComponent(symbol)}`),
  accounts: () => request<Account[]>('/accounts'),
  positions: (accountId: number) => request<Position[]>(`/accounts/${accountId}/positions`),
  ledgers: (accountId: number, cursor: number | null) =>
    request<Page<Ledger>>(`/accounts/${accountId}/ledgers${cursor ? `?cursor=${cursor}` : ''}`),
  orders: (accountId: number, active: boolean) =>
    request<Page<Order>>(`/orders?accountId=${accountId}&active=${active}&size=50`),
  executions: (accountId: number) => request<Page<Execution>>(`/executions?accountId=${accountId}&size=50`),
  placeOrder: (key: string, body: PlaceOrder) => post<Order>('/orders', key, body),
  cancelOrder: (orderId: number) => request<Order>(`/orders/${orderId}`, { method: 'DELETE' }),
  deposit: (key: string, accountId: number, amount: number) =>
    post<{ depositId: number }>(`/accounts/${accountId}/deposits`, key, { amount }),
}
