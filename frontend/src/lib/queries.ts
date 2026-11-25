import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { api } from './api'
import { pollEvery } from './realtime'

/** 로그인한 사용자. */
export const useMe = () => useQuery({ queryKey: ['me'], queryFn: api.me, staleTime: Infinity })

/** 종목과 현재가. */
export const useStocks = () => useQuery({ queryKey: ['stocks'], queryFn: api.stocks, refetchInterval: 2_000 })

/** 호가창. */
export const useBook = (symbol: string) =>
  useQuery({ queryKey: ['book', symbol], queryFn: () => api.book(symbol), refetchInterval: pollEvery(1_000) })

/** 최근 체결. */
export const useTrades = (symbol: string) =>
  useQuery({ queryKey: ['trades', symbol], queryFn: () => api.trades(symbol), refetchInterval: pollEvery(2_000) })

/** 내 첫 번째 계좌. 1단계는 사용자마다 계좌가 하나다. */
export function useAccount() {
  const query = useQuery({ queryKey: ['accounts'], queryFn: api.accounts, refetchInterval: pollEvery(2_000) })
  return { ...query, account: query.data?.[0] }
}

/** 보유 종목. */
export const usePositions = (accountId: number | undefined) =>
  useQuery({
    queryKey: ['positions', accountId], queryFn: () => api.positions(accountId!),
    enabled: accountId !== undefined, refetchInterval: pollEvery(2_000),
  })

/** 주문 내역. */
export const useOrders = (accountId: number | undefined, active: boolean) =>
  useQuery({
    queryKey: ['orders', accountId, active], queryFn: () => api.orders(accountId!, active),
    enabled: accountId !== undefined, refetchInterval: pollEvery(1_000),
  })

/** 체결 내역. */
export const useExecutions = (accountId: number | undefined) =>
  useQuery({
    queryKey: ['executions', accountId], queryFn: () => api.executions(accountId!),
    enabled: accountId !== undefined, refetchInterval: pollEvery(2_000),
  })

/** 현금 원장. */
export const useLedgers = (accountId: number | undefined) =>
  useInfiniteQuery({
    queryKey: ['ledgers', accountId],
    queryFn: ({ pageParam }) => api.ledgers(accountId!, pageParam),
    initialPageParam: null as number | null,
    getNextPageParam: (page) => page.nextCursor,
    enabled: accountId !== undefined,
  })
