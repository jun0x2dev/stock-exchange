import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, type Order, type Stock } from '../lib/api'
import { clock, moment, num, percent, SIDE_LABEL, signed, STATUS_LABEL, trend } from '../lib/format'
import { useExecutions, useOrders, usePositions, useTrades } from '../lib/queries'
import styles from './Activity.module.css'
import { useToast } from './Toast'

const TABS = { open: '대기 주문', orders: '주문 내역', executions: '체결 내역', positions: '보유 종목', trades: '시세' } as const
type Tab = keyof typeof TABS

/** 내 주문·체결·보유 종목과 종목 시세를 탭으로 보여 준다. */
export function Activity({ accountId, stock, stocks }: { accountId: number | undefined; stock: Stock; stocks: Stock[] }) {
  const [tab, setTab] = useState<Tab>('open')
  const names = new Map(stocks.map((s) => [s.symbol, s.name]))
  return (
    <section className={`panel ${styles.panel}`} aria-label="내 거래">
      <div className={styles.tabs} role="tablist">
        {(Object.keys(TABS) as Tab[]).map((key) => (
          <button key={key} type="button" role="tab" id={`tab-${key}`} aria-controls="activity" aria-selected={tab === key} onClick={() => setTab(key)}>{TABS[key]}</button>
        ))}
      </div>
      <div id="activity" role="tabpanel" aria-labelledby={`tab-${tab}`} className={styles.body}>
        {tab === 'open' && <Orders accountId={accountId} names={names} active />}
        {tab === 'orders' && <Orders accountId={accountId} names={names} active={false} />}
        {tab === 'executions' && <Executions accountId={accountId} names={names} />}
        {tab === 'positions' && <Positions accountId={accountId} />}
        {tab === 'trades' && <Trades stock={stock} />}
      </div>
    </section>
  )
}

/** 주문 목록. 대기 주문은 취소할 수 있다. */
function Orders({ accountId, names, active }: { accountId: number | undefined; names: Map<string, string>; active: boolean }) {
  const orders = useOrders(accountId, active)
  const client = useQueryClient()
  const toast = useToast()
  const [canceling, setCanceling] = useState<number | null>(null)

  /** 취소를 요청한다. 그 사이 체결됐을 수 있어 결과는 목록에서 확인한다. */
  async function cancel(order: Order) {
    setCanceling(order.orderId)
    try {
      await api.cancelOrder(order.orderId)
      toast('취소를 요청했어요')
    } catch (e) {
      toast(e instanceof Error ? e.message : '취소하지 못했어요')
    } finally {
      setCanceling(null)
      for (const key of ['orders', 'accounts', 'positions', 'book']) client.invalidateQueries({ queryKey: [key] })
    }
  }

  if (!orders.data?.items.length) {
    return <p className="empty">{orders.isError ? '주문을 불러오지 못했어요.' : active ? '대기 중인 주문이 없어요.' : '아직 주문한 적이 없어요.'}</p>
  }
  return (
    <table className={styles.table}>
      <thead><tr><th>종목</th><th>구분</th><th>가격</th><th>체결/주문</th><th>상태</th><th>{active ? <span className="visually-hidden">취소</span> : '시각'}</th></tr></thead>
      <tbody>
        {orders.data.items.map((order) => (
          <tr key={order.orderId}>
            <td>{names.get(order.symbol)}</td>
            <td className={order.side === 'BUY' ? 'up' : 'down'}>{SIDE_LABEL[order.side]}</td>
            <td>{num(order.price)}원</td>
            <td>{num(order.filledQuantity)}/{num(order.quantity)}주</td>
            <td>{active && order.cancelRequested ? '취소 요청' : STATUS_LABEL[order.status]}</td>
            <td>{active ? (
              <button type="button" className="soft-button" disabled={order.cancelRequested || canceling === order.orderId} onClick={() => cancel(order)}>취소</button>
            ) : moment(order.createdAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** 내 체결 목록. */
function Executions({ accountId, names }: { accountId: number | undefined; names: Map<string, string> }) {
  const executions = useExecutions(accountId)
  if (!executions.data?.items.length) return <p className="empty">아직 체결된 주문이 없어요.</p>
  return (
    <table className={styles.table}>
      <thead><tr><th>종목</th><th>구분</th><th>체결가</th><th>수량</th><th>금액</th><th>시각</th></tr></thead>
      <tbody>
        {executions.data.items.map((e) => (
          <tr key={e.executionId}>
            <td>{names.get(e.symbol)}</td>
            <td className={e.side === 'BUY' ? 'up' : 'down'}>{SIDE_LABEL[e.side]}</td>
            <td>{num(e.price)}원</td>
            <td>{num(e.quantity)}주</td>
            <td>{num(e.price * e.quantity)}원</td>
            <td>{moment(e.executedAt)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** 보유 종목과 평가손익. */
export function Positions({ accountId }: { accountId: number | undefined }) {
  const positions = usePositions(accountId)
  const held = positions.data?.filter((p) => p.quantity > 0) ?? []
  if (held.length === 0) return <p className="empty">{positions.isError ? '보유 종목을 불러오지 못했어요.' : '보유한 종목이 없어요.'}</p>
  return (
    <table className={styles.table}>
      <thead><tr><th>종목</th><th>수량</th><th>평균단가</th><th>현재가</th><th>평가금액</th><th>평가손익</th></tr></thead>
      <tbody>
        {held.map((p) => (
          <tr key={p.symbol}>
            <td><Link to={`/stocks/${p.symbol}`}>{p.name}</Link></td>
            <td>{num(p.quantity)}주</td>
            <td>{num(p.averagePrice)}원</td>
            <td>{num(p.lastPrice)}원</td>
            <td>{num(p.evaluation)}원</td>
            <td className={trend(p.unrealizedPnl)}>{signed(p.unrealizedPnl)}원 ({percent(p.unrealizedPnl, p.totalCost)})</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** 종목의 최근 체결. 직전 체결보다 오르고 내린 것을 색으로 나타낸다. */
function Trades({ stock }: { stock: Stock }) {
  const trades = useTrades(stock.symbol).data ?? []
  if (trades.length === 0) return <p className="empty">아직 체결이 없어요.</p>
  return (
    <table className={styles.table}>
      <thead><tr><th>체결가</th><th>직전 대비</th><th>수량</th><th>시각</th></tr></thead>
      <tbody>
        {trades.map((trade, i) => {
          const change = trade.price - (trades[i + 1]?.price ?? trade.price)
          return (
            <tr key={trade.seq}>
              <td>{num(trade.price)}원</td>
              <td className={trend(change)}>{change === 0 ? '0' : signed(change)}</td>
              <td>{num(trade.quantity)}주</td>
              <td>{clock(trade.executedAt)}</td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
