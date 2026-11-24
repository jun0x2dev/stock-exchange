import { NavLink } from 'react-router'
import { num, percent, signed, trend } from '../lib/format'
import { useStocks } from '../lib/queries'
import styles from './Watchlist.module.css'

/** 전체 종목과 기준가 대비 등락. */
export function Watchlist() {
  const stocks = useStocks()
  return (
    <section className={`panel ${styles.panel}`} aria-labelledby="watchlist">
      <h2 id="watchlist" className={styles.title}>종목</h2>
      <ul className={styles.list}>
        {stocks.data?.map((stock) => {
          const change = stock.lastPrice - stock.basePrice
          return (
            <li key={stock.symbol}>
              <NavLink to={`/stocks/${stock.symbol}`} className={({ isActive }) => `${styles.row} ${isActive ? styles.current : ''}`}>
                <span className={styles.mark} aria-hidden="true">{stock.name.slice(0, 1)}</span>
                <span className={styles.name}>{stock.name}</span>
                <span className={styles.price}>{num(stock.lastPrice)}원</span>
                <span className={`${styles.change} ${trend(change)}`}>{signed(change)} ({percent(change, stock.basePrice)})</span>
              </NavLink>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
