import { useQueryClient } from '@tanstack/react-query'
import { Link, NavLink, useNavigate } from 'react-router'
import { api, type Me } from '../lib/api'
import styles from './Header.module.css'
import { useToast } from './Toast'

/** 화면 위쪽의 이동 메뉴와 로그아웃. */
export function Header({ me }: { me: Me }) {
  const client = useQueryClient()
  const navigate = useNavigate()
  const toast = useToast()

  /** 세션을 끊고 화면에 남은 계좌 정보를 지운다. */
  async function logout() {
    try {
      await api.logout()
    } catch {
      toast('로그아웃하지 못했어요. 다시 시도하세요.')
      return
    }
    client.clear()
    navigate('/login')
  }

  return (
    <header className={styles.header}>
      <Link to="/" className={styles.brand}>
        <img src="/favicon.svg" alt="" width="26" height="26" />
        모의거래
      </Link>
      <nav className={styles.nav}>
        {me.role === 'CUSTOMER' ? (
          <>
            <NavLink to="/stocks" className={({ isActive }) => (isActive ? styles.current : undefined)}>거래</NavLink>
            <NavLink to="/account" className={({ isActive }) => (isActive ? styles.current : undefined)}>내 자산</NavLink>
          </>
        ) : <NavLink to="/admin" className={({ isActive }) => (isActive ? styles.current : undefined)}>운영</NavLink>}
      </nav>
      <span className={styles.user}>{me.loginId}</span>
      <button type="button" className="soft-button" onClick={logout}>로그아웃</button>
    </header>
  )
}
