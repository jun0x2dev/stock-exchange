/** 가격대에 맞는 호가 단위. 안내용이며 서버가 다시 검증한다. */
export function tickSize(price: number): number {
  if (price < 2_000) return 1
  if (price < 5_000) return 5
  if (price < 20_000) return 10
  if (price < 50_000) return 50
  if (price < 200_000) return 100
  return price < 500_000 ? 500 : 1_000
}

/** 한 호가 위나 아래의 가격. 단위에 맞지 않는 값이면 그 방향의 가장 가까운 호가로 맞춘다. */
export function stepPrice(price: number, direction: 1 | -1): number {
  const tick = tickSize(direction === 1 ? price : Math.max(price - 1, 0))
  return direction === 1 ? (Math.floor(price / tick) + 1) * tick : Math.max((Math.ceil(price / tick) - 1) * tick, 0)
}
