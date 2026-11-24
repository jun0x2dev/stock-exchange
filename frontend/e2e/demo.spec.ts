import { expect, test, type Locator } from '@playwright/test'
import { mkdirSync, rmSync, writeFileSync } from 'node:fs'

const PASSWORD = process.env.EXCHANGE_DEMO_PASSWORD ?? ''
const OUT = '../screenshots/demo'

// README 시연 GIF의 프레임을 만든다. DEMO=1 npx playwright test demo --project=mobile 뒤에 make-demo-gif.py를 실행한다.
test.skip(!process.env.DEMO, '시연을 녹화할 때만 실행한다')

test('시연 녹화', async ({ page }) => {
  test.setTimeout(120_000)
  rmSync(OUT, { recursive: true, force: true })
  mkdirSync(OUT, { recursive: true })
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' })

  const frames: { file: string; ms: number; caption: string; tap?: { x: number; y: number } }[] = []
  let caption = ''
  /** 현재 화면을 한 장 저장한다. 누른 위치는 GIF를 만들 때 그린다. */
  const shot = async (ms: number, tap?: { x: number; y: number }) => {
    const file = `${String(frames.length).padStart(3, '0')}.png`
    await page.screenshot({ path: `${OUT}/${file}`, scale: 'css' })
    frames.push({ file, ms, caption, tap })
  }
  /** 누를 곳을 보여 준 뒤 누르고, 바뀐 화면을 저장한다. */
  const tap = async (target: Locator, ms = 1100, settle = 500) => {
    await target.scrollIntoViewIfNeeded()
    const box = (await target.boundingBox())!
    await shot(650, { x: box.x + box.width / 2, y: box.y + box.height / 2 })
    await target.click()
    await page.waitForTimeout(settle)
    await shot(ms)
  }
  const form = () => page.locator('form:visible')
  const buy = () => page.getByRole('button', { name: '구매하기', exact: true }).last()
  const confirm = page.getByRole('heading', { name: /할까요/ }).locator('..')

  caption = '로그인'
  await page.goto('/login')
  await page.waitForTimeout(800)
  await shot(900)
  await page.getByLabel('아이디').fill('demo')
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await tap(page.getByRole('button', { name: '로그인' }), 1500, 1500)

  caption = '호가를 누르면 그 가격이 주문서에 들어갑니다'
  await tap(page.getByRole('list', { name: '판매 호가' }).getByRole('button').last())
  await tap(buy())
  caption = '수량을 고르고 주문 내용을 확인합니다'
  await tap(form().getByRole('button', { name: '10%' }))
  await tap(form().getByRole('button', { name: '구매하기' }), 1500)
  caption = '주문하면 매칭 엔진이 체결합니다'
  await tap(confirm.getByRole('button', { name: '구매하기' }), 1400, 1200)
  await tap(page.getByRole('tab', { name: '체결 내역' }), 1500, 900)
  await tap(page.getByRole('tab', { name: '보유 종목' }), 1500, 900)

  caption = '주문 응답이 끊기면?'
  let drop = true
  await page.route('**/api/v1/orders', async (route) => {
    if (route.request().method() !== 'POST' || !drop) return route.continue()
    // 서버는 주문을 처리했지만 응답이 끊긴 상황을 만든다.
    drop = false
    await route.fetch()
    return route.abort('connectionreset')
  })
  const low = await page.locator('dt', { hasText: '하한가' }).locator('+ dd').innerText()
  await page.getByRole('tab', { name: '대기 주문' }).click()
  await tap(buy(), 600)
  await form().getByLabel('가격', { exact: true }).fill(low)
  await form().getByLabel('수량', { exact: true }).fill('5')
  await tap(form().getByRole('button', { name: '구매하기' }), 900)
  await tap(confirm.getByRole('button', { name: '구매하기' }), 1900, 900)
  caption = '같은 멱등키로 다시 보내 한 번만 접수합니다'
  await tap(confirm.getByRole('button', { name: '다시 시도' }), 2200, 1300)

  caption = '대기 주문을 취소하면 묶인 돈이 풀립니다'
  const cancel = page.getByRole('tabpanel').getByRole('button', { name: '취소' })
  await expect(cancel).toHaveCount(1)
  await tap(cancel.first(), 1500, 1500)

  caption = '잔고의 모든 변화는 원장에 남습니다'
  await tap(page.getByRole('link', { name: '내 자산' }), 1600, 1200)
  await page.getByRole('heading', { name: '현금 거래내역' }).scrollIntoViewIfNeeded()
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
  await page.waitForTimeout(400)
  await shot(2600)

  writeFileSync(`${OUT}/frames.json`, JSON.stringify(frames))
})
