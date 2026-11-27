import { expect, test, type Page } from '@playwright/test'

const PASSWORD = process.env.EXCHANGE_DEMO_PASSWORD ?? ''

/** 체험용 사용자로 로그인한다. */
async function login(page: Page, loginId: string) {
  await page.goto('/login')
  await page.getByLabel('아이디').fill(loginId)
  await page.getByLabel('비밀번호').fill(PASSWORD)
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible()
}

/** 로그아웃한다. */
async function logout(page: Page) {
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page).toHaveURL(/\/login$/)
}

test('운영자가 요청하고 다른 사람인 승인자가 승인하면 고객 거래내역에 정정이 남는다', async ({ page }) => {
  // 다른 프로젝트·다른 실행의 입금과 섞이지 않는 금액을 쓴다.
  const amount = (1_000 + Math.floor(Math.random() * 9_000)) * 100
  const label = `demo · ${amount.toLocaleString('ko-KR')}원`

  await login(page, 'demo')
  await page.goto('/account')
  await page.getByLabel('모의 입금').fill(String(amount))
  await page.getByRole('button', { name: '입금하기' }).click()
  await expect(page.getByText('입금했어요')).toBeVisible()
  await logout(page)

  await login(page, 'operator')
  await expect(page).toHaveURL(/\/admin$/)
  await expect(page.getByRole('link', { name: '내 자산' })).toHaveCount(0)
  const deposit = page.getByRole('listitem').filter({ hasText: label }).first()
  await deposit.getByRole('button', { name: /정정 요청/ }).click()
  await page.getByLabel('사유').fill('같은 입금이 두 번 들어옴')
  await page.getByRole('button', { name: '요청하기' }).click()
  await expect(page.getByText('정정을 요청했어요')).toBeVisible()
  const request = page.getByRole('listitem').filter({ hasText: '같은 입금이 두 번 들어옴' }).filter({ hasText: label }).first()
  await expect(request).toContainText('승인 대기')
  // 요청한 사람에게는 결정 버튼이 없다. 서버도 거부한다.
  await expect(request.getByRole('button', { name: /승인/ })).toHaveCount(0)
  await logout(page)

  await login(page, 'approver')
  await expect(page.getByRole('heading', { name: '최근 입금·입고' })).toHaveCount(0)
  const pending = page.getByRole('listitem').filter({ hasText: label }).filter({ hasText: '승인 대기' }).first()
  await pending.getByRole('button', { name: /승인$/ }).click()
  await page.getByRole('button', { name: '승인하기' }).click()
  await expect(page.getByText('승인했어요')).toBeVisible()
  await expect(page.getByRole('listitem').filter({ hasText: label }).filter({ hasText: 'approver 승인' }).first()).toBeVisible()
  await logout(page)

  await login(page, 'demo')
  await page.goto('/account')
  await expect(page.getByRole('listitem').filter({ hasText: '입금 정정' }).first()).toContainText(`−${amount.toLocaleString('ko-KR')}원`)
  await logout(page)

  await login(page, 'auditor')
  await page.getByRole('button', { name: '지금 검증하기' }).click()
  await expect(page.getByText('이상 없음')).toBeVisible()
})

test('운영자가 지금 대사하면 결과가 이력 맨 위에 남고, 승인자는 실행할 수 없다', async ({ page }) => {
  await login(page, 'operator')
  const history = page.getByRole('heading', { name: '대사 결과' }).locator('..')
  await history.getByRole('button', { name: '지금 대사하기' }).click()
  await expect(page.getByText('대사 결과 모두 일치해요')).toBeVisible()
  await expect(history.getByRole('listitem').first()).toContainText(/일치.*수동/)
  await expect(page.getByRole('heading', { name: '이상거래 경보' })).toBeVisible()
  await logout(page)

  await login(page, 'approver')
  await expect(page.getByRole('heading', { name: '대사 결과' })).toBeVisible()
  await expect(page.getByRole('button', { name: '지금 대사하기' })).toHaveCount(0)
})

test('고객은 운영 화면에 들어갈 수 없다', async ({ page }) => {
  await login(page, 'demo')
  await page.goto('/admin')
  await expect(page).toHaveURL(/\/stocks\//)
  await expect(page.getByRole('link', { name: '운영' })).toHaveCount(0)
})
