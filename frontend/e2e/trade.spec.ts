import { expect, test, type Page } from '@playwright/test'

const PASSWORD = process.env.EXCHANGE_DEMO_PASSWORD ?? ''

/** 체험용 사용자로 로그인하고 첫 종목 화면을 연다. */
async function login(page: Page) {
  await page.goto('/')
  await expect(page).toHaveURL(/\/login$/)
  await page.getByLabel('아이디').fill('demo')
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page).toHaveURL(/\/stocks\//)
}

/** 화면 크기에 맞는 주문서를 연다. */
async function openForm(page: Page, side: '구매' | '판매') {
  const bar = page.getByRole('button', { name: `${side}하기`, exact: true })
  if (page.viewportSize()!.width < 768) await bar.last().click()
  const form = page.locator('form:visible')
  await form.getByRole('group', { name: '주문 종류' }).getByRole('button', { name: side, exact: true }).click()
  return form
}

/** 주문 확인 창. 좁은 화면에서는 주문서 창 위에 겹쳐 뜬다. */
const confirmation = (page: Page) => page.getByRole('heading', { name: /할까요/ }).locator('..')

/** 하한가에 한 주를 구매하는 주문의 확인 창을 연다. 체결되지 않아 다른 테스트에 영향을 주지 않는다. */
async function reviewLowBid(page: Page) {
  const form = await openForm(page, '구매')
  const low = await page.locator('dt', { hasText: '하한가' }).locator('+ dd').innerText()
  await form.getByLabel('가격', { exact: true }).fill(low)
  await form.getByLabel('수량', { exact: true }).fill('1')
  await form.getByRole('button', { name: '구매하기' }).click()
  return confirmation(page)
}

/** 내 거래 탭을 연다. */
async function openTab(page: Page, name: string) {
  if (page.viewportSize()!.width < 768) await page.getByRole('button', { name: '내 거래', exact: true }).click()
  await page.getByRole('tab', { name }).click()
  return page.getByRole('tabpanel')
}

test('틀린 비밀번호로는 들어갈 수 없다', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('아이디').fill('nobody')
  await page.getByLabel('비밀번호').fill('wrong')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.getByRole('alert')).toContainText('아이디 또는 비밀번호')
})

test('최우선 매도 호가로 사면 체결 내역과 보유 종목에 나타난다', async ({ page }) => {
  await login(page)
  const ask = page.getByRole('list', { name: '판매 호가' }).getByRole('button').last()
  const price = (await ask.getAttribute('aria-label'))!.split('원')[0]!
  await ask.click()
  const form = await openForm(page, '구매')
  await expect(form.getByLabel('가격', { exact: true })).toHaveValue(price)
  await form.getByLabel('수량', { exact: true }).fill('1')
  await form.getByRole('button', { name: '구매하기' }).click()
  await confirmation(page).getByRole('button', { name: '구매하기' }).click()
  await expect(page.getByText('구매 주문을 접수했어요')).toBeVisible()
  await expect((await openTab(page, '체결 내역')).getByRole('row').nth(1)).toContainText(`${price}원`)
  await expect((await openTab(page, '보유 종목')).getByRole('row').nth(1)).toContainText('주')
})

test('대기 주문을 취소하면 목록에서 사라진다', async ({ page }) => {
  await login(page)
  const before = await (await openTab(page, '대기 주문')).getByRole('button', { name: '취소' }).count()
  const dialog = await reviewLowBid(page)
  await dialog.getByRole('button', { name: '구매하기' }).click()
  const panel = await openTab(page, '대기 주문')
  await expect(panel.getByRole('button', { name: '취소' })).toHaveCount(before + 1)
  await panel.getByRole('button', { name: '취소' }).first().click()
  await expect(panel.getByRole('button', { name: '취소' })).toHaveCount(before)
})

test('응답을 잃어버린 주문을 다시 시도해도 한 번만 접수된다', async ({ page }) => {
  await login(page)
  const keys: string[] = []
  let drop = true
  await page.route('**/api/v1/orders', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    keys.push((await route.request().allHeaders())['idempotency-key']!)
    if (!drop) return route.continue()
    // 서버는 주문을 처리했지만 응답이 끊긴 상황을 만든다.
    drop = false
    await route.fetch()
    return route.abort('connectionreset')
  })
  const before = await (await openTab(page, '대기 주문')).getByRole('button', { name: '취소' }).count()
  const dialog = await reviewLowBid(page)
  await dialog.getByRole('button', { name: '구매하기' }).click()
  await expect(dialog.getByRole('alert')).toContainText('한 번만 접수돼요')
  await dialog.getByRole('button', { name: '다시 시도' }).click()
  await expect(page.getByText('구매 주문을 접수했어요')).toBeVisible()
  expect(keys).toHaveLength(2)
  expect(keys[1]).toBe(keys[0])
  const panel = await openTab(page, '대기 주문')
  await expect(panel.getByRole('button', { name: '취소' })).toHaveCount(before + 1)
  await panel.getByRole('button', { name: '취소' }).first().click()
  await expect(panel.getByRole('button', { name: '취소' })).toHaveCount(before)
})

test('세션 쿠키는 스크립트에서 읽을 수 없고 브라우저 저장소에 인증 정보가 없다', async ({ page, context }) => {
  await login(page)
  const session = (await context.cookies()).find((c) => c.name === 'JSESSIONID')!
  expect(session.httpOnly).toBe(true)
  expect(session.sameSite).toBe('Strict')
  expect(await page.evaluate(() => document.cookie)).not.toContain('JSESSIONID')
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
})

test('주문하면 조회를 기다리지 않고 실시간으로 호가 잔량이 바뀐다', async ({ page }) => {
  // 첫 조회만 허용한다. 그 뒤의 변화는 실시간 연결로만 알 수 있다.
  let polls = 0
  await page.route('**/api/v1/orderbooks/**', (route) => (polls++ === 0 ? route.continue() : route.abort()))
  await login(page)
  await expect(page.getByText('실시간', { exact: true })).toBeVisible()
  const bid = page.getByRole('list', { name: '구매 호가' }).getByRole('button').first()
  const [, price, quantity] = (await bid.getAttribute('aria-label'))!.match(/^([\d,]+)원, 구매 잔량 ([\d,]+)주$/)!
  const form = await openForm(page, '구매')
  await form.getByLabel('가격', { exact: true }).fill(price!)
  await form.getByLabel('수량', { exact: true }).fill('1')
  await form.getByRole('button', { name: '구매하기' }).click()
  await confirmation(page).getByRole('button', { name: '구매하기' }).click()
  await expect(page.getByText('구매 주문을 접수했어요')).toBeVisible()
  const grown = (Number(quantity!.replaceAll(',', '')) + 1).toLocaleString('ko-KR')
  await expect(page.locator(`button[aria-label="${price}원, 구매 잔량 ${grown}주"]`)).toHaveCount(1)
  await (await openTab(page, '대기 주문')).getByRole('button', { name: '취소' }).first().click()
  await expect(page.locator(`button[aria-label="${price}원, 구매 잔량 ${quantity}주"]`)).toHaveCount(1)
})

test('로그아웃하면 계좌 화면에 들어갈 수 없다', async ({ page }) => {
  await login(page)
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await page.goto('/account')
  await expect(page).toHaveURL(/\/login$/)
})
