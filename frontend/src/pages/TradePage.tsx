import { useRef, useState } from 'react'
import { useParams } from 'react-router'
import { Activity } from '../components/Activity'
import { OrderBook } from '../components/OrderBook'
import { OrderForm } from '../components/OrderForm'
import { PriceChart } from '../components/PriceChart'
import { Watchlist } from '../components/Watchlist'
import type { Side, Stock } from '../lib/api'
import { num, percent, signed, trend } from '../lib/format'
import { useAccount, usePositions, useStocks } from '../lib/queries'
import styles from './TradePage.module.css'

/** 주소의 종목으로 거래 화면을 연다. 종목이 바뀌면 주문서를 새로 시작한다. */
export function TradePage() {
  const { symbol } = useParams()
  const stocks = useStocks()
  const stock = stocks.data?.find((s) => s.symbol === symbol)
  if (!stock) {
    return <p className="empty">{stocks.data ? '없는 종목이에요. 위쪽 거래 메뉴에서 다른 종목을 고르세요.' : stocks.isError ? '종목을 불러오지 못했어요.' : '불러오는 중이에요.'}</p>
  }
  return <Trade key={stock.symbol} stock={stock} stocks={stocks.data!} />
}

/** 시세, 호가, 주문서, 내 거래를 한 화면에 둔다. 좁은 화면에서는 탭과 아래쪽 주문 버튼으로 나눈다. */
function Trade({ stock, stocks }: { stock: Stock; stocks: Stock[] }) {
  const { account } = useAccount()
  const positions = usePositions(account?.accountId)
  const [side, setSide] = useState<Side>('BUY')
  const [price, setPrice] = useState(stock.lastPrice)
  const [view, setView] = useState<'book' | 'chart' | 'activity'>('book')
  const sheet = useRef<HTMLDialogElement>(null)
  const change = stock.lastPrice - stock.basePrice

  const form = {
    stock, account, side, price, onSide: setSide, onPrice: setPrice,
    position: positions.data?.find((p) => p.symbol === stock.symbol),
  }

  /** 좁은 화면에서 주문서를 연다. */
  function openSheet(next: Side) {
    setSide(next)
    sheet.current?.showModal()
  }

  return (
    <main className={styles.page} data-view={view}>
      <div className={styles.watch}><Watchlist /></div>
      <div className={styles.head}>
        <h1>{stock.name} <span>{stock.symbol}</span>{stock.status === 'HALTED' && <strong className={styles.halted}>거래 정지</strong>}</h1>
        <p className={styles.price}>{num(stock.lastPrice)}원</p>
        <p className={styles.change}>기준가보다 <b className={trend(change)}>{signed(change)}원 ({percent(change, stock.basePrice)})</b></p>
        <dl className={styles.limits}>
          <div><dt>상한가</dt><dd className="up">{num(stock.upperLimit)}</dd></div>
          <div><dt>하한가</dt><dd className="down">{num(stock.lowerLimit)}</dd></div>
        </dl>
      </div>
      <div className={styles.views} role="group" aria-label="보기">
        {([['book', '호가'], ['chart', '차트'], ['activity', '내 거래']] as const).map(([key, label]) => (
          <button key={key} type="button" aria-pressed={view === key} onClick={() => setView(key)}>{label}</button>
        ))}
      </div>
      <div className={styles.chart}><PriceChart stock={stock} /></div>
      <div className={styles.book}><OrderBook stock={stock} price={price} onPick={setPrice} /></div>
      <div className={styles.activity}><Activity accountId={account?.accountId} stock={stock} stocks={stocks} /></div>
      <div className={styles.order}><OrderForm {...form} /></div>

      <div className={styles.bar}>
        <button type="button" className={styles.sell} onClick={() => openSheet('SELL')}>판매하기</button>
        <button type="button" className={styles.buy} onClick={() => openSheet('BUY')}>구매하기</button>
      </div>
      <dialog ref={sheet} className={styles.sheet} aria-label="주문하기" onClick={(e) => e.target === sheet.current && sheet.current.close()}>
        <button type="button" className={styles.close} onClick={() => sheet.current?.close()}>닫기</button>
        <OrderForm {...form} onDone={() => { sheet.current?.close(); setView('activity') }} />
      </dialog>
    </main>
  )
}
