import { useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { api, ApiError } from '../lib/api'
import styles from './LoginPage.module.css'

/** 로그인 화면. 비밀번호는 저장하지 않는다. */
export function LoginPage() {
  const [loginId, setLoginId] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)
  const client = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()

  /** 로그인하고 원래 보려던 화면으로 돌아간다. */
  async function submit(event: FormEvent) {
    event.preventDefault()
    setSending(true)
    setError('')
    try {
      await api.login(loginId, password)
      client.clear()
      client.setQueryData(['me'], await api.me())
      navigate((location.state as { from?: string } | null)?.from ?? '/', { replace: true })
    } catch (e) {
      setPassword('')
      setError(e instanceof ApiError && e.code === 'LOGIN_FAILED' ? e.message
        : '로그인하지 못했어요. 새로고침한 뒤 다시 시도하세요.')
    } finally {
      setSending(false)
    }
  }

  return (
    <main className={styles.page}>
      <form className={styles.form} onSubmit={submit}>
        <img src="/favicon.svg" alt="" width="44" height="44" />
        <h1>모의거래에<br />로그인하세요</h1>
        <p className={styles.note}>가상의 돈과 종목으로 거래해요. 실제 주문은 나가지 않아요.</p>
        <label htmlFor="login-id">아이디</label>
        <input id="login-id" className="field" autoComplete="username" autoCapitalize="none" maxLength={50} required
          value={loginId} onChange={(e) => setLoginId(e.target.value)} />
        <label htmlFor="password">비밀번호</label>
        <input id="password" className="field" type="password" autoComplete="current-password" maxLength={128} required
          value={password} onChange={(e) => setPassword(e.target.value)} />
        <p className={styles.error} role="alert">{error}</p>
        <button type="submit" className="solid-button" disabled={sending}>{sending ? '확인하는 중' : '로그인'}</button>
      </form>
    </main>
  )
}
