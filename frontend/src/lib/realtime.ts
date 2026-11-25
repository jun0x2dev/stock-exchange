import { useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import type { Book, Trade } from './api'

type Message = { type: 'MARKET'; book: Book; trades: Trade[] } | { type: 'ACCOUNT' }

const ACCOUNT_KEYS = ['accounts', 'positions', 'orders', 'executions', 'ledgers']
const MAX_RETRY_MILLIS = 10_000

let connected = false

/** 실시간으로 받는 동안은 가끔만 다시 조회한다. 끊기면 원래 주기로 조회해 화면이 멈추지 않게 한다. */
export const pollEvery = (millis: number) => () => (connected ? 10_000 : millis)

/** 종목의 호가와 체결을 실시간으로 받아 조회 결과에 반영한다. 연결돼 있으면 true를 반환한다. */
export function useRealtime(symbol: string): boolean {
  const client = useQueryClient()
  const [live, setLive] = useState(false)

  useEffect(() => {
    let socket: WebSocket
    let timer: ReturnType<typeof setTimeout>
    let retry = 1_000
    let stopped = false

    /** 연결 상태를 조회 주기와 화면에 함께 반영한다. */
    const change = (next: boolean) => {
      connected = next
      setLive(next)
    }
    /** 연결하고, 끊기면 간격을 늘려 가며 다시 연결한다. */
    const open = () => {
      const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
      socket = new WebSocket(`${protocol}://${location.host}/ws/quotes/${encodeURIComponent(symbol)}`)
      socket.onmessage = (event) => {
        retry = 1_000
        change(true)
        const message = JSON.parse(event.data as string) as Message
        if (message.type === 'MARKET') {
          client.setQueryData(['book', symbol], message.book)
          client.setQueryData(['trades', symbol], message.trades)
        } else {
          for (const key of ACCOUNT_KEYS) client.invalidateQueries({ queryKey: [key] })
        }
      }
      socket.onclose = () => {
        if (stopped) return
        change(false)
        timer = setTimeout(open, retry)
        retry = Math.min(retry * 2, MAX_RETRY_MILLIS)
      }
    }
    open()
    return () => {
      stopped = true
      connected = false
      clearTimeout(timer)
      socket.close()
    }
  }, [client, symbol])

  return live
}
