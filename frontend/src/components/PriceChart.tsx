import { useCallback, useState, type PointerEvent } from 'react'
import type { Stock } from '../lib/api'
import { clock, num } from '../lib/format'
import { useTrades } from '../lib/queries'
import styles from './PriceChart.module.css'

const HEIGHT = 210
const PAD = { top: 16, right: 84, bottom: 16, left: 8 }

/** 최근 체결가의 흐름. 가로축은 시간 간격이 아니라 체결 순서다. */
export function PriceChart({ stock }: { stock: Stock }) {
  const trades = useTrades(stock.symbol)
  const [width, setWidth] = useState(460)
  // 화면 폭이 달라져도 글자와 선 굵기가 그대로이도록 실제 폭으로 그린다.
  const measure = useCallback((element: HTMLDivElement) => {
    const observer = new ResizeObserver(([entry]) => entry && setWidth(entry.contentRect.width))
    observer.observe(element)
    return () => observer.disconnect()
  }, [])
  const [hover, setHover] = useState<number | null>(null)
  const points = trades.data?.toReversed() ?? []
  const last = points.at(-1)

  const prices = [...points.map((t) => t.price), stock.basePrice]
  const low = Math.min(...prices)
  const high = Math.max(...prices)
  const span = high - low || 1
  const x = (i: number) => PAD.left + (points.length < 2 ? 0 : (i / (points.length - 1)) * (width - PAD.left - PAD.right))
  const y = (price: number) => PAD.top + ((high - price) / span) * (HEIGHT - PAD.top - PAD.bottom)
  const color = last && last.price < stock.basePrice ? 'var(--down)' : 'var(--up)'
  const active = hover === null ? undefined : points[hover]

  /** 포인터에서 가장 가까운 체결을 고른다. */
  function pick(event: PointerEvent<SVGSVGElement>) {
    const box = event.currentTarget.getBoundingClientRect()
    const position = ((event.clientX - box.left) / box.width) * width - PAD.left
    const index = Math.round((position / (width - PAD.left - PAD.right)) * (points.length - 1))
    setHover(Math.min(Math.max(index, 0), points.length - 1))
  }

  return (
    <section className={`panel ${styles.panel}`} aria-labelledby="chart">
      <h2 id="chart" className={styles.title}>
        최근 체결가 {points.length > 0 && <small>{points.length}건</small>}
      </h2>
      {points.length < 2 ? (
        <p className="empty">{trades.isError ? '체결 내역을 불러오지 못했어요.' : '체결이 두 건 이상 쌓이면 흐름을 보여 드려요.'}</p>
      ) : (
        <div className={styles.plot} ref={measure}>
          <svg viewBox={`0 0 ${width} ${HEIGHT}`} role="img" onPointerMove={pick} onPointerLeave={() => setHover(null)}
            aria-label={`최근 체결 ${points.length}건, 최저 ${num(low)}원, 최고 ${num(high)}원, 마지막 ${num(last!.price)}원`}>
            <line className={styles.base} x1={PAD.left} x2={width - PAD.right} y1={y(stock.basePrice)} y2={y(stock.basePrice)} />
            {Math.abs(y(stock.basePrice) - y(last!.price)) > 18 && (
              <text className={styles.label} x={width - PAD.right + 10} y={y(stock.basePrice) + 4}>기준 {num(stock.basePrice)}</text>
            )}
            <polyline className={styles.line} stroke={color} points={points.map((t, i) => `${x(i)},${y(t.price)}`).join(' ')} />
            <circle className={styles.dot} cx={x(points.length - 1)} cy={y(last!.price)} r="4.5" fill={color} />
            <text className={styles.lastLabel} x={width - PAD.right + 10} y={y(last!.price) + 5}>{num(last!.price)}</text>
            {active && (
              <>
                <line className={styles.cross} x1={x(hover!)} x2={x(hover!)} y1={PAD.top} y2={HEIGHT - PAD.bottom} />
                <circle className={styles.dot} cx={x(hover!)} cy={y(active.price)} r="5" fill={color} />
              </>
            )}
          </svg>
          {active && (
            <p className={styles.tip} style={{ left: `${Math.min((x(hover!) / width) * 100, 72)}%` }}>
              <strong>{num(active.price)}원</strong> {num(active.quantity)}주<br />{clock(active.executedAt)}
            </p>
          )}
        </div>
      )}
    </section>
  )
}
