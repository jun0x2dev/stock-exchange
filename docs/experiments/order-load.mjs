// 체험용 사용자로 주문과 취소를 동시에 보내고, 접수 응답(201)을 받은 주문 ID를 파일에 남긴다.
// 실행: EXCHANGE_DEMO_PASSWORD=... node order-load.mjs http://localhost:8080 <초> <결과 파일>
import { writeFileSync } from 'node:fs'

const [base, seconds, out] = process.argv.slice(2)
const USERS = ['demo', 'maker', 'taker']
const WORKERS_PER_USER = 8
const accepted = new Set()
const counts = {}
const latencies = []
let alive = true

/** 로그인하고 쿠키를 관리하는 호출 함수를 만든다. */
async function session(loginId) {
  const jar = {}
  const call = async (path, init = {}) => {
    const response = await fetch(`${base}/api/v1${path}`, {
      ...init, signal: AbortSignal.timeout(5000),
      headers: {
        ...init.headers, 'X-XSRF-TOKEN': jar['XSRF-TOKEN'] ?? '',
        cookie: Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; '),
      },
    })
    for (const cookie of response.headers.getSetCookie()) {
      const [name, value] = cookie.split(';')[0].split('=')
      jar[name] = value
    }
    return { status: response.status, body: await response.json() }
  }
  await call('/auth/me')
  const login = await call('/auth/login', {
    method: 'POST', body: new URLSearchParams({ loginId, password: process.env.EXCHANGE_DEMO_PASSWORD }),
  })
  if (login.status !== 200) throw new Error(`로그인 실패: ${loginId}`)
  await call('/auth/me')
  return call
}

const stocks = (await (await session('demo'))('/stocks')).body.data
const deadline = Date.now() + Number(seconds) * 1000

/** 서버가 끊기거나 시간이 끝날 때까지 주문 4건에 취소 1건 꼴로 보낸다. */
async function work(call, accountId) {
  const mine = []
  while (alive && Date.now() < deadline) {
    try {
      if (mine.length > 0 && Math.random() < 0.2) {
        await call(`/orders/${mine.splice(Math.floor(Math.random() * mine.length), 1)[0]}`, { method: 'DELETE' })
        continue
      }
      const stock = stocks[Math.floor(Math.random() * stocks.length)]
      const tick = [[2e3, 1], [5e3, 5], [2e4, 10], [5e4, 50], [2e5, 100], [5e5, 500]].find(([limit]) => stock.basePrice * 0.95 < limit)[1] * 10
      const price = Math.round((stock.basePrice * (0.99 + Math.random() * 0.02)) / tick) * tick
      const started = performance.now()
      const { status, body } = await call('/orders', {
        method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ accountId, symbol: stock.symbol, side: Math.random() < 0.5 ? 'BUY' : 'SELL', orderType: 'LIMIT', price, quantity: 1 + Math.floor(Math.random() * 10) }),
      })
      const result = status === 201 ? 'ACCEPTED' : body.error?.code
      counts[result] = (counts[result] ?? 0) + 1
      if (status === 201) {
        latencies.push(performance.now() - started)
        accepted.add(body.data.orderId)
        mine.push(body.data.orderId)
      }
    } catch {
      alive = false
    }
  }
}

const jobs = []
for (const user of USERS) {
  for (let i = 0; i < WORKERS_PER_USER; i++) {
    const call = await session(user)
    const account = (await call('/accounts')).body.data[0].accountId
    jobs.push(() => work(call, account))
  }
}
console.log('READY')
const begin = performance.now()
await Promise.all(jobs.map((job) => job()))
latencies.sort((a, b) => a - b)
const at = (p) => Math.round(latencies[Math.floor(latencies.length * p)])
console.log(JSON.stringify({ seconds: +((performance.now() - begin) / 1000).toFixed(1), killed: !alive, counts, p50: at(0.5), p99: at(0.99) }))
writeFileSync(out, [...accepted].join('\n'))
