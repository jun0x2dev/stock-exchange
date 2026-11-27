import { expect, test, type Locator, type Page } from '@playwright/test'
import { mkdirSync, rmSync, writeFileSync } from 'node:fs'

const PASSWORD = process.env.EXCHANGE_DEMO_PASSWORD ?? ''

// README 시연 GIF의 프레임을 만든다. DEMO=1 npx playwright test demo --project=mobile 뒤에 make-demo-gif.py를 실행한다.
test.skip(!process.env.DEMO, '시연을 녹화할 때만 실행한다')

type Frame = { file: string; ms: number; caption: string; tap?: { x: number; y: number } }

/** 화면을 프레임으로 저장하는 도구. 프레임은 ../screenshots/{name}에 쌓인다. */
async function recorder(page: Page, name: string) {
  const out = `../screenshots/${name}`
  rmSync(out, { recursive: true, force: true })
  mkdirSync(out, { recursive: true })
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' })
  const frames: Frame[] = []
  const state = { caption: '' }
  /** 현재 화면을 한 장 저장한다. 누른 위치는 GIF를 만들 때 그린다. */
  const shot = async (ms: number, tap?: { x: number; y: number }) => {
    const file = `${String(frames.length).padStart(3, '0')}.png`
    await page.screenshot({ path: `${out}/${file}`, scale: 'css' })
    frames.push({ file, ms, caption: state.caption, tap })
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
  /** 프레임 목록을 남긴다. */
  const save = () => writeFileSync(`${out}/frames.json`, JSON.stringify(frames))
  return { state, shot, tap, save }
}

test('시연 녹화', async ({ page }) => {
  test.setTimeout(120_000)
  const { state, shot, tap, save } = await recorder(page, 'demo')
  const form = () => page.locator('form:visible')
  const buy = () => page.getByRole('button', { name: '구매하기', exact: true }).last()
  const confirm = page.getByRole('heading', { name: /할까요/ }).locator('..')

  state.caption = '로그인'
  await page.goto('/login')
  await page.waitForTimeout(800)
  await shot(900)
  await page.getByLabel('아이디').fill('demo')
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await tap(page.getByRole('button', { name: '로그인' }), 1500, 1500)

  state.caption = '봇이 모의 시세를 따라 호가를 냅니다'
  await expect(page.getByText('실시간', { exact: true })).toBeVisible()
  // 시세가 움직이는 모습을 담는다.
  for (let i = 0; i < 9; i++) {
    await page.waitForTimeout(1200)
    await shot(650)
  }
  await page.getByRole('button', { name: '차트', exact: true }).click()
  for (let i = 0; i < 4; i++) {
    await page.waitForTimeout(1200)
    await shot(650)
  }
  await page.getByRole('button', { name: '호가', exact: true }).click()

  state.caption = '호가를 누르면 그 가격이 주문서에 들어갑니다'
  // 시세가 움직여도 체결되도록 두세 호가 높게 산다. 실제로는 더 싼 호가부터 체결된다.
  await tap(page.getByRole('list', { name: '판매 호가' }).getByRole('button').first())
  await tap(buy())
  state.caption = '수량을 고르고 주문 내용을 확인합니다'
  await tap(form().getByRole('button', { name: '10%' }))
  await tap(form().getByRole('button', { name: '구매하기' }), 1500)
  state.caption = '주문하면 매칭 엔진이 체결합니다'
  await tap(confirm.getByRole('button', { name: '구매하기' }), 1400, 1200)
  await tap(page.getByRole('tab', { name: '체결 내역' }), 1500, 900)
  await tap(page.getByRole('tab', { name: '보유 종목' }), 1500, 900)

  state.caption = '주문 응답이 끊기면?'
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
  state.caption = '같은 멱등키로 다시 보내 한 번만 접수합니다'
  await tap(confirm.getByRole('button', { name: '다시 시도' }), 2200, 1300)

  state.caption = '대기 주문을 취소하면 묶인 돈이 풀립니다'
  const cancel = page.getByRole('tabpanel').getByRole('button', { name: '취소' })
  await expect(cancel).toHaveCount(1)
  await tap(cancel.first(), 900)
  await expect(cancel).toHaveCount(0)
  await shot(1500)
  // 다음 장면에 알림이 남지 않게 한다.
  await expect(page.getByText('취소를 요청했어요')).toHaveCount(0)

  state.caption = '잔고의 모든 변화는 원장에 남습니다'
  await tap(page.getByRole('link', { name: '내 자산' }), 1600, 1200)
  await page.getByRole('heading', { name: '현금 거래내역' }).scrollIntoViewIfNeeded()
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
  await page.waitForTimeout(400)
  await shot(2600)

  save()
})

test('운영 시연 녹화', async ({ page }) => {
  test.setTimeout(120_000)
  const { state, shot, tap, save } = await recorder(page, 'admin-demo')
  const amount = 1_000_000
  /** 화면을 남기지 않고 다른 사용자로 바꾼다. */
  const switchTo = async (loginId: string) => {
    if (await page.getByRole('button', { name: '로그아웃' }).isVisible()) {
      await page.getByRole('button', { name: '로그아웃' }).click()
      await expect(page).toHaveURL(/\/login$/)
    } else {
      await page.goto('/login')
    }
    await page.getByLabel('아이디').fill(loginId)
    await page.getByLabel('비밀번호').fill(PASSWORD)
    await page.getByRole('button', { name: '로그인' }).click()
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible()
    await page.waitForTimeout(900)
  }
  const dialog = page.locator('dialog[open]')
  /** 제목이 화면 위쪽에 오도록 스크롤한다. */
  const toTop = async (heading: string) => {
    await page.getByRole('heading', { name: heading }).evaluate((e) => e.scrollIntoView({ block: 'start' }))
    await page.waitForTimeout(400)
  }

  state.caption = '고객 계좌에 100만 원이 잘못 입금됐습니다'
  await switchTo('demo')
  await page.goto('/account')
  await page.waitForTimeout(900)
  await tap(page.getByRole('button', { name: '+100만' }), 700)
  await tap(page.getByRole('button', { name: '입금하기' }), 1500, 900)

  state.caption = '운영자는 정정을 요청만 할 수 있습니다'
  await switchTo('operator')
  await shot(1600)
  await tap(page.getByRole('listitem').filter({ hasText: `demo · ${amount.toLocaleString('ko-KR')}원` }).first()
    .getByRole('button', { name: /정정 요청/ }), 900)
  await dialog.getByLabel('사유').fill('같은 입금이 두 번 들어옴')
  await shot(900)
  await tap(dialog.getByRole('button', { name: '요청하기' }), 900, 900)
  await toTop('원장 정정 요청')
  await shot(2000)

  state.caption = '다른 사람인 승인자가 확인하고 승인합니다'
  await switchTo('approver')
  await shot(1400)
  await tap(page.getByRole('button', { name: /승인$/ }).first(), 1800)
  await tap(dialog.getByRole('button', { name: '승인하기' }), 2000, 900)

  state.caption = '원래 기록은 두고 반대 전표가 남습니다'
  await switchTo('demo')
  await page.goto('/account')
  await page.waitForTimeout(600)
  await toTop('현금 거래내역')
  await shot(2600)

  state.caption = '감사 로그가 고쳐지지 않았는지 검증합니다'
  await switchTo('auditor')
  await shot(1200)
  await tap(page.getByRole('button', { name: '지금 검증하기' }), 2800, 1200)

  state.caption = '잔고와 원장이 맞는지 매일 대사합니다'
  await toTop('대사 결과')
  await shot(900)
  await tap(page.getByRole('button', { name: '지금 대사하기' }), 2800, 1200)

  state.caption = '허수성 호가를 찾아 경보합니다'
  // 공격자(spoofer)를 켜고 녹화해야 경보가 있다.
  await expect(page.getByText('허수성 호가').first()).toBeVisible({ timeout: 60_000 })
  // 앞 장면의 알림이 경보를 가리지 않게 한다.
  await expect(page.getByText('대사 결과 모두 일치해요')).toHaveCount(0)
  await toTop('이상거래 경보')
  await shot(3200)
  save()
})
