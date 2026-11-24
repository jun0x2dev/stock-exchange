import { useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState, type FormEvent } from 'react'
import { api, ApiError, type Account, type PlaceOrder, type Position, type Side, type Stock } from '../lib/api'
import { useAttempt } from '../lib/attempt'
import { digits, num, SIDE_LABEL } from '../lib/format'
import { stepPrice, tickSize } from '../lib/price'
import styles from './OrderForm.module.css'
import { useToast } from './Toast'

interface Props {
  stock: Stock
  account: Account | undefined
  position: Position | undefined
  side: Side
  price: number
  onSide: (side: Side) => void
  onPrice: (price: number) => void
  onDone?: () => void
}

/** 지정가 주문서. 금액 계산은 안내용이고 서버가 다시 검증한다. */
export function OrderForm({ stock, account, position, side, price, onSide, onPrice, onDone }: Props) {
  const [quantity, setQuantity] = useState(0)
  const [pending, setPending] = useState<PlaceOrder | null>(null)
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<{ message: string; uncertain: boolean } | null>(null)
  // 넓은 화면과 좁은 화면의 주문서가 함께 있으므로 ID가 겹치지 않게 한다.
  const id = useId()
  const dialog = useRef<HTMLDialogElement>(null)
  const attempt = useAttempt()
  const client = useQueryClient()
  const toast = useToast()

  const cash = account?.availableCash ?? 0
  const sellable = position?.sellableQuantity ?? 0
  const max = side === 'BUY' ? (price > 0 ? Math.floor(cash / price) : 0) : sellable
  const total = price * quantity
  const problem = findProblem()

  /** 보내기 전에 알 수 있는 문제를 찾는다. */
  function findProblem(): string | null {
    if (stock.status === 'HALTED') return '거래가 정지된 종목이에요.'
    if (price < stock.lowerLimit || price > stock.upperLimit) {
      return `${num(stock.lowerLimit)}원부터 ${num(stock.upperLimit)}원까지 주문할 수 있어요.`
    }
    if (price % tickSize(price) !== 0) return `${num(tickSize(price))}원 단위로 입력하세요.`
    if (quantity > max) return side === 'BUY' ? '주문 가능 금액을 넘었어요.' : '팔 수 있는 수량을 넘었어요.'
    return null
  }

  /** 주문 내용을 고정해 확인 창을 연다. */
  function review(event: FormEvent) {
    event.preventDefault()
    if (!account || problem || quantity === 0) return
    setPending({ accountId: account.accountId, symbol: stock.symbol, side, orderType: 'LIMIT', price, quantity })
    setError(null)
    dialog.current?.showModal()
  }

  /** 확인한 내용을 그대로 보낸다. 결과를 모르면 같은 멱등키로 다시 보낼 수 있다. */
  async function send() {
    if (!pending || sending) return
    setSending(true)
    setError(null)
    try {
      await attempt(pending, (key) => api.placeOrder(key, pending))
      toast(`${SIDE_LABEL[pending.side]} 주문을 접수했어요`)
      setQuantity(0)
      dialog.current?.close()
      onDone?.()
      for (const key of ['accounts', 'positions', 'orders', 'book']) client.invalidateQueries({ queryKey: [key] })
    } catch (e) {
      setError({
        message: e instanceof ApiError ? e.message : '주문이 접수됐는지 확인하지 못했어요. 다시 시도해도 한 번만 접수돼요.',
        uncertain: !(e instanceof ApiError),
      })
    } finally {
      setSending(false)
    }
  }

  return (
    <form className={`panel ${styles.panel} ${side === 'BUY' ? styles.buy : styles.sell}`} onSubmit={review}>
      <h2 className="visually-hidden">주문하기</h2>
      <div className={styles.sides} role="group" aria-label="주문 종류">
        {(['BUY', 'SELL'] as const).map((value) => (
          <button key={value} type="button" aria-pressed={side === value} onClick={() => onSide(value)}>{SIDE_LABEL[value]}</button>
        ))}
      </div>

      <div className={styles.field}>
        <label htmlFor={`${id}-price`}>가격</label>
        <div className={styles.stepper}>
          <button type="button" aria-label="한 호가 내리기" onClick={() => onPrice(stepPrice(price, -1))}>−</button>
          <input id={`${id}-price`} inputMode="numeric" autoComplete="off" value={price === 0 ? '' : num(price)} onChange={(e) => onPrice(digits(e.target.value))} />
          <span>원</span>
          <button type="button" aria-label="한 호가 올리기" onClick={() => onPrice(stepPrice(price, 1))}>+</button>
        </div>
        <button type="button" className={styles.current} onClick={() => onPrice(stock.lastPrice)}>현재가 {num(stock.lastPrice)}원 입력</button>
      </div>

      <div className={styles.field}>
        <label htmlFor={`${id}-quantity`}>수량</label>
        <div className={styles.stepper}>
          <button type="button" aria-label="한 주 줄이기" onClick={() => setQuantity(Math.max(quantity - 1, 0))}>−</button>
          <input id={`${id}-quantity`} inputMode="numeric" autoComplete="off" placeholder={`최대 ${num(max)}`} value={quantity === 0 ? '' : num(quantity)}
            onChange={(e) => setQuantity(Math.min(digits(e.target.value), 1_000_000))} />
          <span>주</span>
          <button type="button" aria-label="한 주 늘리기" onClick={() => setQuantity(quantity + 1)}>+</button>
        </div>
        <div className={styles.ratios}>
          {[10, 25, 50, 100].map((ratio) => (
            <button key={ratio} type="button" className="soft-button" disabled={max === 0}
              onClick={() => setQuantity(Math.min(Math.floor((max * ratio) / 100), 1_000_000))}>{ratio === 100 ? '최대' : `${ratio}%`}</button>
          ))}
        </div>
      </div>

      <dl className={styles.summary}>
        <div><dt>{side === 'BUY' ? '주문 가능 금액' : '팔 수 있는 수량'}</dt><dd>{side === 'BUY' ? `${num(cash)}원` : `${num(sellable)}주`}</dd></div>
        <div className={styles.total}><dt>총 주문 금액</dt><dd>{num(total)}원</dd></div>
      </dl>
      <p className={styles.problem} role="status">{problem}</p>
      <button type="submit" className={styles.submit} disabled={!account || problem !== null || quantity === 0}>{SIDE_LABEL[side]}하기</button>

      <dialog ref={dialog} className={styles.dialog} aria-labelledby={`${id}-title`} onCancel={(e) => sending && e.preventDefault()}>
        {pending && (
          <div className={pending.side === 'BUY' ? styles.buy : styles.sell}>
            <h3 id={`${id}-title`}>
              {stock.name} <em>{num(pending.quantity)}주</em>를<br />
              <em>{num(pending.price)}원</em>에 {SIDE_LABEL[pending.side]}할까요?
            </h3>
            <dl className={styles.summary}>
              <div><dt>주문 유형</dt><dd>지정가</dd></div>
              <div className={styles.total}><dt>총 주문 금액</dt><dd>{num(pending.price * pending.quantity)}원</dd></div>
            </dl>
            {error && <p className={styles.error} role="alert">{error.message}</p>}
            <div className={styles.actions}>
              <button type="button" className="soft-button" disabled={sending} onClick={() => dialog.current?.close()}>닫기</button>
              {(!error || error.uncertain) && (
                <button type="button" className={styles.submit} disabled={sending} onClick={send}>
                  {sending ? '보내는 중' : error ? '다시 시도' : `${SIDE_LABEL[pending.side]}하기`}
                </button>
              )}
            </div>
          </div>
        )}
      </dialog>
    </form>
  )
}
