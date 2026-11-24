import { useRef } from 'react'
import { UncertainError } from './api'

/**
 * 사용자의 시도마다 멱등키를 하나 쓰고, 결과를 모르는 시도를 같은 내용으로 다시 하면 같은 키를 쓴다.
 * 응답만 잃어버린 주문이 두 번 접수되는 것을 막는다.
 */
export function useAttempt() {
  const uncertain = useRef<{ content: string; key: string } | null>(null)

  return async function attempt<T>(body: unknown, send: (key: string) => Promise<T>): Promise<T> {
    const content = JSON.stringify(body)
    const key = uncertain.current?.content === content ? uncertain.current.key : crypto.randomUUID()
    try {
      const result = await send(key)
      uncertain.current = null
      return result
    } catch (error) {
      uncertain.current = error instanceof UncertainError ? { content, key } : null
      throw error
    }
  }
}
