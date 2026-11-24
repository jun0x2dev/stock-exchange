import type { OrderStatus, Side } from './api'

const number = new Intl.NumberFormat('ko-KR')
const time = new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
const dateTime = new Intl.DateTimeFormat('ko-KR', {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
})

/** 천 단위 구분 숫자. */
export const num = (value: number) => number.format(value)

/** 부호를 붙인 숫자. 음수 기호는 폭이 같은 문자를 쓴다. */
export const signed = (value: number) => (value > 0 ? '+' : value < 0 ? '−' : '') + number.format(Math.abs(value))

/** 기준값 대비 등락률. */
export const percent = (value: number, base: number) => (base === 0 ? '0.00%' : `${Math.abs((value / base) * 100).toFixed(2)}%`)

/** 시각. */
export const clock = (iso: string) => time.format(new Date(iso))

/** 날짜와 시각. */
export const moment = (iso: string) => dateTime.format(new Date(iso))

/** 오르면 up, 내리면 down. 색을 고르는 클래스 이름으로 쓴다. */
export const trend = (value: number) => (value > 0 ? 'up' : value < 0 ? 'down' : 'flat')

/** 입력한 글자에서 숫자만 읽는다. */
export const digits = (text: string) => Number(text.replace(/\D/g, '').slice(0, 10))

export const SIDE_LABEL: Record<Side, string> = { BUY: '구매', SELL: '판매' }

export const STATUS_LABEL: Record<OrderStatus, string> = {
  ACCEPTED: '접수 중', OPEN: '대기', PARTIALLY_FILLED: '일부 체결', FILLED: '체결', CANCELED: '취소',
}
