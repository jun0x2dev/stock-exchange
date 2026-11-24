import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import { App } from './App'
import './global.css'
import { ApiError } from './lib/api'

const client: QueryClient = new QueryClient({
  queryCache: new QueryCache({
    // 세션이 끝나면 어느 화면에서든 로그인으로 보낸다.
    onError: (error, query) => {
      if (error instanceof ApiError && error.status === 401 && query.queryKey[0] !== 'me') {
        client.invalidateQueries({ queryKey: ['me'] })
      }
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: 500,
      retry: (count, error) => !(error instanceof ApiError) && count < 2,
    },
    // 변경 요청은 자동으로 다시 보내지 않는다. 재시도는 사용자가 같은 멱등키로 한다.
    mutations: { retry: false },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={client}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
)
