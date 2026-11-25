import { useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Positions } from '../components/Activity'
import { useToast } from '../components/Toast'
import { api, ApiError, type Ledger } from '../lib/api'
import { useAttempt } from '../lib/attempt'
import { digits, moment, num, percent, signed, trend } from '../lib/format'
import { useAccount, useLedgers, usePositions } from '../lib/queries'
import styles from './AccountPage.module.css'

const LEDGER_LABEL: Record<Ledger['type'], string> = {
  DEPOSIT: '입금', RESERVE: '주문 예약', RELEASE: '예약 해제', BUY: '주식 구매', SELL: '주식 판매', REVERSAL: '입금 정정',
}

/** 총자산, 보유 종목, 모의 입금, 현금 거래내역. */
export function AccountPage() {
  const { account, isSuccess } = useAccount()
  const positions = usePositions(account?.accountId)
  const held = positions.data ?? []
  const evaluation = held.reduce((sum, p) => sum + p.evaluation, 0)
  const unrealized = held.reduce((sum, p) => sum + p.unrealizedPnl, 0)
  const realized = held.reduce((sum, p) => sum + p.realizedPnl, 0)
  const cost = held.reduce((sum, p) => sum + p.totalCost, 0)

  if (!account) return <p className="empty">{isSuccess ? '이 사용자에게는 계좌가 없어요.' : '불러오는 중이에요.'}</p>
  return (
    <main className={styles.page}>
      <section className={styles.summary}>
        <h1>총자산</h1>
        <p className={styles.total}>{num(account.cashBalance + evaluation)}원</p>
        <p className={styles.pnl}>평가손익 <b className={trend(unrealized)}>{signed(unrealized)}원 ({percent(unrealized, cost)})</b></p>
        <dl className={styles.figures}>
          <div><dt>주문 가능 금액</dt><dd>{num(account.availableCash)}원</dd></div>
          <div><dt>주문에 묶인 금액</dt><dd>{num(account.reservedCash)}원</dd></div>
          <div><dt>주식 평가금액</dt><dd>{num(evaluation)}원</dd></div>
          <div><dt>실현손익</dt><dd className={trend(realized)}>{signed(realized)}원</dd></div>
        </dl>
      </section>
      <DepositForm accountId={account.accountId} />
      <section className={`panel ${styles.section}`}>
        <h2>보유 종목</h2>
        <div className={styles.scroll}><Positions accountId={account.accountId} /></div>
      </section>
      <Ledgers accountId={account.accountId} />
    </main>
  )
}

/** 모의 입금. 결과를 모르면 같은 멱등키로 다시 보낸다. */
function DepositForm({ accountId }: { accountId: number }) {
  const [amount, setAmount] = useState(0)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState('')
  const attempt = useAttempt()
  const client = useQueryClient()
  const toast = useToast()

  /** 입금을 요청한다. */
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (amount === 0 || sending) return
    setSending(true)
    setError('')
    try {
      await attempt({ accountId, amount }, (key) => api.deposit(key, accountId, amount))
      toast(`${num(amount)}원을 입금했어요`)
      setAmount(0)
      for (const key of ['accounts', 'ledgers']) client.invalidateQueries({ queryKey: [key] })
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '입금됐는지 확인하지 못했어요. 다시 시도해도 한 번만 입금돼요.')
    } finally {
      setSending(false)
    }
  }

  return (
    <form className={`panel ${styles.section} ${styles.deposit}`} onSubmit={submit}>
      <h2><label htmlFor="amount">모의 입금</label></h2>
      <input id="amount" className="field" inputMode="numeric" autoComplete="off" placeholder="금액 (최대 10억 원)" value={amount === 0 ? '' : num(amount)}
        onChange={(e) => setAmount(Math.min(digits(e.target.value), 1_000_000_000))} />
      <div className={styles.presets}>
        {[100_000, 1_000_000, 10_000_000].map((value) => (
          <button key={value} type="button" className="soft-button" onClick={() => setAmount(Math.min(amount + value, 1_000_000_000))}>+{num(value / 10_000)}만</button>
        ))}
      </div>
      {error && <p role="alert">{error}</p>}
      <button type="submit" className="solid-button" disabled={amount === 0 || sending}>입금하기</button>
    </form>
  )
}

/** 원장에 남은 현금 거래내역. 잔고가 왜 이 금액인지 모두 설명된다. */
function Ledgers({ accountId }: { accountId: number }) {
  const ledgers = useLedgers(accountId)
  const rows = ledgers.data?.pages.flatMap((page) => page.items) ?? []
  return (
    <section className={`panel ${styles.section}`}>
      <h2>현금 거래내역</h2>
      {rows.length === 0 ? <p className="empty">{ledgers.isError ? '거래내역을 불러오지 못했어요.' : '거래내역이 없어요.'}</p> : (
        <ul className={styles.ledgers}>
          {rows.map((row) => (
            <li key={row.ledgerId}>
              <span><b>{LEDGER_LABEL[row.type]}</b><small>{moment(row.createdAt)}</small></span>
              <span className={styles.delta}>
                {row.cashDelta !== 0 && <b>{signed(row.cashDelta)}원</b>}
                {row.reservedDelta !== 0 && <small>예약 {signed(row.reservedDelta)}원</small>}
              </span>
            </li>
          ))}
        </ul>
      )}
      {ledgers.hasNextPage && <button type="button" className="soft-button" disabled={ledgers.isFetchingNextPage} onClick={() => ledgers.fetchNextPage()}>더 보기</button>}
    </section>
  )
}
