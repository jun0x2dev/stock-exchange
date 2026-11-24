import { createContext, use, useEffect, useState, type ReactNode } from 'react'
import styles from './Toast.module.css'

const ToastContext = createContext<(message: string) => void>(() => {})

/** 처리 결과를 잠깐 보여 주는 알림. 화면 낭독기에도 전달한다. */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toast, setToast] = useState<{ message: string; id: number } | null>(null)
  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(null), 3_000)
    return () => clearTimeout(timer)
  }, [toast])
  return (
    <ToastContext value={(message) => setToast({ message, id: Date.now() })}>
      {children}
      <div className={styles.area} role="status">{toast && <p key={toast.id} className={styles.toast}>{toast.message}</p>}</div>
    </ToastContext>
  )
}

/** 알림을 띄우는 함수. */
export const useToast = () => use(ToastContext)
