// 부하 측정용 계좌에 현금과 주식을 넣는다. 실행: EXCHANGE_DEMO_PASSWORD=... node bench-deposit.mjs http://localhost:8081
const base = `${process.argv[2]}/api/v1`

/** 로그인하고 쿠키를 관리하는 호출 함수를 만든다. */
async function session(loginId) {
  const jar = {}
  const call = async (path, init = {}) => {
    const response = await fetch(base + path, {
      ...init, headers: { ...init.headers, 'X-XSRF-TOKEN': jar['XSRF-TOKEN'] ?? '', cookie: Object.entries(jar).map(([k, v]) => `${k}=${v}`).join('; ') },
    })
    for (const cookie of response.headers.getSetCookie()) {
      const [name, value] = cookie.split(';')[0].split('=')
      jar[name] = value
    }
    const body = await response.json()
    if (!body.success && path !== '/auth/me') throw new Error(`${path} ${response.status}`)
    return body.data
  }
  await call('/auth/me')
  await call('/auth/login', { method: 'POST', body: new URLSearchParams({ loginId, password: process.env.EXCHANGE_DEMO_PASSWORD }) })
  await call('/auth/me')
  return call
}

const post = (body) => ({ method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'bench' + JSON.stringify(body).length + (body.symbol ?? '') }, body: JSON.stringify(body) })
const operator = await session('operator')
const stocks = await operator('/stocks')
for (const user of ['bench1', 'bench2', 'bench3']) {
  const call = await session(user)
  for (const { accountId } of await call('/accounts')) {
    await call(`/accounts/${accountId}/deposits`, post({ amount: 1_000_000_000 }))
    for (const { symbol } of stocks) await operator(`/accounts/${accountId}/stock-deposits`, post({ symbol, quantity: 100_000 }))
  }
}
console.log('done')
