import { expect, test } from '@playwright/test'

const PASSWORD = process.env.EXCHANGE_DEMO_PASSWORD ?? ''

// 화면 캡처를 저장소 루트의 screenshots 폴더에 저장한다. 이 폴더는 git에 올리지 않는다.
for (const colorScheme of ['light', 'dark'] as const) {
  test(`화면 캡처 (${colorScheme})`, async ({ page }, info) => {
    await page.emulateMedia({ colorScheme, reducedMotion: 'reduce' })
    const errors: string[] = []
    page.on('pageerror', (error) => errors.push(error.message))
    /** 글꼴과 데이터가 자리 잡은 뒤 캡처하고, 가로로 넘치지 않는지 확인한다. */
    const capture = async (name: string) => {
      await page.waitForTimeout(800)
      await page.screenshot({ path: `../screenshots/${info.project.name}-${colorScheme}-${name}.png` })
      expect(await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)).toBeLessThanOrEqual(0)
    }

    await page.goto('/login')
    await capture('1-login')
    await page.getByLabel('아이디').fill('demo')
    await page.getByLabel('비밀번호').fill(PASSWORD)
    await page.getByRole('button', { name: '로그인' }).click()
    await expect(page.getByRole('heading', { name: '호가' })).toBeVisible()
    await capture('2-trade')

    const mobile = page.viewportSize()!.width < 768
    if (mobile) {
      await page.getByRole('button', { name: '차트', exact: true }).click()
      await capture('3-chart')
      await page.getByRole('button', { name: '구매하기', exact: true }).last().click()
      await capture('4-order-form')
    }
    const form = page.locator('form:visible')
    await form.getByRole('button', { name: '10%' }).click()
    await form.getByRole('button', { name: '구매하기' }).click()
    await capture('5-order-confirm')
    // 캡처만 하므로 주문을 보내지 않고 닫는다.
    await page.getByRole('heading', { name: /할까요/ }).locator('..').getByRole('button', { name: '닫기' }).click()
    if (mobile) await page.getByRole('button', { name: '닫기' }).click()

    if (mobile) await page.getByRole('button', { name: '내 거래', exact: true }).click()
    await page.getByRole('tab', { name: '보유 종목' }).click()
    await capture('6-positions')
    await page.getByRole('tab', { name: '체결 내역' }).click()
    await capture('7-executions')
    await page.goto('/account')
    await expect(page.getByRole('heading', { name: '총자산' })).toBeVisible()
    await capture('8-account')
    expect(errors).toEqual([])
  })
}
