import type { Level, Stock } from '../lib/api'
import { num, percent, trend } from '../lib/format'
import { useBook } from '../lib/queries'
import styles from './OrderBook.module.css'

const DEPTH = 10

/** 가격을 가운데 둔 호가창. 행을 누르면 그 가격이 주문서에 들어간다. */
export function OrderBook({ stock, price, onPick }: { stock: Stock; price: number; onPick: (price: number) => void }) {
  const book = useBook(stock.symbol)
  const asks = book.data?.asks.slice(0, DEPTH) ?? []
  const bids = book.data?.bids.slice(0, DEPTH) ?? []
  const largest = Math.max(1, ...asks.map((l) => l.quantity), ...bids.map((l) => l.quantity))
  const total = (levels: Level[]) => levels.reduce((sum, level) => sum + level.quantity, 0)

  /** 한 호가 행. 막대 길이는 보이는 호가 중 가장 큰 잔량을 기준으로 한다. */
  function row(level: Level, side: 'ask' | 'bid') {
    const change = level.price - stock.basePrice
    const name = side === 'ask' ? '판매' : '구매'
    return (
      <li key={level.price}>
        <button
          type="button" className={`${styles.row} ${styles[side]} ${level.price === stock.lastPrice ? styles.last : ''}`}
          aria-pressed={level.price === price} onClick={() => onPick(level.price)}
          aria-label={`${num(level.price)}원, ${name} 잔량 ${num(level.quantity)}주`}
        >
          <span className={styles.quantity}>
            <span className={styles.bar} style={{ width: `${(level.quantity / largest) * 100}%` }} />
            <span className={styles.amount}>{num(level.quantity)}</span>
          </span>
          <span className={`${styles.price} ${trend(change)}`}>
            {num(level.price)}
            <small>{change < 0 ? '−' : ''}{percent(change, stock.basePrice)}</small>
          </span>
        </button>
      </li>
    )
  }

  return (
    <section className={`panel ${styles.panel}`} aria-labelledby="order-book">
      <h2 id="order-book" className={styles.title}>호가</h2>
      {asks.length === 0 && bids.length === 0 ? (
        <p className="empty">{book.isError ? '호가를 불러오지 못했어요.' : '아직 주문이 없어요. 첫 주문을 내 보세요.'}</p>
      ) : (
        <>
          <ol className={styles.asks} aria-label="판매 호가">{asks.toReversed().map((level) => row(level, 'ask'))}</ol>
          <ol className={styles.bids} aria-label="구매 호가">{bids.map((level) => row(level, 'bid'))}</ol>
          <p className={styles.total}>
            <span className="down">판매 {num(total(asks))}</span>
            <span>잔량 합계</span>
            <span className="up">구매 {num(total(bids))}</span>
          </p>
        </>
      )}
    </section>
  )
}
