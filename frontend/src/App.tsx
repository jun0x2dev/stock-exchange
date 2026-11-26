import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router'
import { Header } from './components/Header'
import { ToastProvider } from './components/Toast'
import { ApiError } from './lib/api'
import { useMe, useStocks } from './lib/queries'
import { AccountPage } from './pages/AccountPage'
import { AdminPage } from './pages/AdminPage'
import { LoginPage } from './pages/LoginPage'
import { TradePage } from './pages/TradePage'

/** 화면 주소와 로그인 여부에 따라 보여 줄 화면을 고른다. */
export function App() {
  return (
    <ToastProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireLogin />}>
          <Route path="/stocks/:symbol" element={<TradePage />} />
          <Route path="/account" element={<AccountPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="*" element={<FirstStock />} />
        </Route>
      </Routes>
    </ToastProvider>
  )
}

/** 로그인하지 않았으면 로그인 화면으로 보낸다. 실제 접근 통제는 서버가 한다. */
function RequireLogin() {
  const me = useMe()
  const location = useLocation()
  if (me.error instanceof ApiError && me.error.status === 401) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (!me.data) {
    return <p className="empty" role="status">{me.isError ? '서버와 연결하지 못했어요. 잠시 후 새로고침하세요.' : '불러오는 중이에요.'}</p>
  }
  return (
    <>
      <Header me={me.data} />
      <Outlet />
    </>
  )
}

/** 직원은 운영 화면으로, 고객은 첫 종목의 거래 화면으로 보낸다. */
function FirstStock() {
  const me = useMe()
  const stocks = useStocks()
  const first = stocks.data?.[0]
  if (me.data && me.data.role !== 'CUSTOMER') return <Navigate to="/admin" replace />
  return first ? <Navigate to={`/stocks/${first.symbol}`} replace /> : <p className="empty">종목을 불러오는 중이에요.</p>
}
