// k6: 정해진 속도로 지정가 주문을 보내고 단계별 접수 지연을 잰다.
// ACCOUNTS=3이면 세 계좌에 집중하고, 30이면 나눠 보낸다.
import http from 'k6/http'
import { check } from 'k6'
import { Trend, Counter } from 'k6/metrics'

const BASE = `${__ENV.BASE}/api/v1`
const RATES = [5, 10, 20, 40]
const SECONDS = 15
const placed = new Trend('placed', true)
const result = new Counter('result')

export const options = {
  scenarios: Object.fromEntries(RATES.map((rate, i) => [`r${rate}`, {
    executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration: `${SECONDS}s`, startTime: `${i * (SECONDS + 3)}s`,
    preAllocatedVUs: 24, maxVUs: 24, gracefulStop: '3s', tags: { rate: String(rate) },
  }])),
  thresholds: Object.fromEntries(RATES.flatMap((rate) => [
    [`placed{rate:${rate}}`, ['p(99)>=0']], [`result{rate:${rate},code:ACCEPTED}`, ['count>=0']], [`dropped_iterations{rate:${rate}}`, ['count>=0']],
  ])),
  summaryTrendStats: ['med', 'p(95)', 'p(99)', 'max'],
}

/** 세션 쿠키가 Secure라서 http에서는 직접 헤더로 보낸다. */
function login(loginId) {
  // 앞 사용자의 쿠키가 남아 있으면 서버가 CSRF 토큰을 새로 주지 않는다.
  const jar = new http.CookieJar()
  const csrf = http.get(`${BASE}/auth/me`, { jar }).cookies['XSRF-TOKEN'][0].value
  const response = http.post(`${BASE}/auth/login`, { loginId, password: __ENV.PASSWORD }, {
    headers: { Cookie: `XSRF-TOKEN=${csrf}`, 'X-XSRF-TOKEN': csrf }, jar: new http.CookieJar(),
  })
  if (response.status !== 200) throw new Error(`로그인 실패: ${loginId} ${response.status}`)
  const session = response.headers['Set-Cookie'].match(/JSESSIONID=([^;]+)/)[1]
  const headers = { Cookie: `XSRF-TOKEN=${csrf}; JSESSIONID=${session}`, 'X-XSRF-TOKEN': csrf, 'Content-Type': 'application/json' }
  return http.get(`${BASE}/accounts`, { headers, jar: new http.CookieJar() }).json('data').map((a) => ({ accountId: a.accountId, headers }))
}

export function setup() {
  const perUser = Number(__ENV.ACCOUNTS) / 3
  const traders = ['bench1', 'bench2', 'bench3'].flatMap((id) => login(id).slice(0, perUser))
  return { traders, stocks: http.get(`${BASE}/stocks`, { headers: traders[0].headers, jar: new http.CookieJar() }).json('data') }
}

export default function ({ traders, stocks }) {
  const { accountId, headers } = traders[Math.floor(Math.random() * traders.length)]
  const stock = stocks[Math.floor(Math.random() * stocks.length)]
  // 가격대가 바뀌는 경계에서도 호가 단위에 맞도록 큰 단위로 반올림한다.
  const unit = stock.basePrice >= 200000 ? 500 : stock.basePrice >= 50000 ? 100 : stock.basePrice >= 10000 ? 50 : 10
  const price = Math.round((stock.basePrice * (0.995 + Math.random() * 0.01)) / unit) * unit
  const response = http.post(`${BASE}/orders`, JSON.stringify({
    accountId, symbol: stock.symbol, side: Math.random() < 0.5 ? 'BUY' : 'SELL', orderType: 'LIMIT', price, quantity: 1 + Math.floor(Math.random() * 5),
  }), { headers: { ...headers, 'Idempotency-Key': `${__VU}-${__ITER}-${Math.random()}` }, jar: new http.CookieJar(), tags: { name: 'place' } })
  check(response, { accepted: (r) => r.status === 201 })
  result.add(1, { code: response.status === 201 ? 'ACCEPTED' : String(response.json('error.code')) })
  if (response.status === 201) placed.add(response.timings.duration)
}

export function handleSummary(data) {
  const value = (name) => data.metrics[name]?.values ?? {}
  const rows = RATES.map((rate) => {
    const t = value(`placed{rate:${rate}}`)
    return { rate, accepted: value(`result{rate:${rate},code:ACCEPTED}`).count ?? 0, dropped: value(`dropped_iterations{rate:${rate}}`).count ?? 0, p50: Math.round(t.med), p95: Math.round(t['p(95)']), p99: Math.round(t['p(99)']) }
  })
  return { stdout: rows.map((r) => JSON.stringify(r)).join('\n') + '\n' }
}
