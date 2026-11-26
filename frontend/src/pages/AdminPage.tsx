import { useQueryClient } from '@tanstack/react-query'
import { useId, useRef, useState, type FormEvent } from 'react'
import { Navigate } from 'react-router'
import { useToast } from '../components/Toast'
import { api, ApiError, type Adjustment, type AuditVerification, type Deposit, type Role } from '../lib/api'
import { moment, num } from '../lib/format'
import { useAdjustments, useDeposits, useMe } from '../lib/queries'
import styles from './AdminPage.module.css'

const ROLE: Record<Exclude<Role, 'CUSTOMER'>, { label: string; duty: string }> = {
  OPERATOR: { label: '운영자', duty: '잘못된 입금·입고의 정정을 요청해요. 승인은 다른 사람이 해요.' },
  APPROVER: { label: '승인자', duty: '운영자가 요청한 정정을 확인하고 승인하거나 거절해요.' },
  AUDITOR: { label: '감사 담당자', duty: '감사 로그가 기록된 뒤 바뀌거나 지워지지 않았는지 검증해요.' },
}

const STATUS: Record<Adjustment['status'], string> = { REQUESTED: '승인 대기', APPROVED: '승인', REJECTED: '거절' }

/** 입금은 원, 입고는 주로 표시한다. */
const amountOf = (row: { symbol: string | null; amount: number }) =>
  row.symbol ? `${row.symbol} ${num(row.amount)}주` : `${num(row.amount)}원`

/** 서버가 거절한 이유, 또는 처리 여부를 모른다는 안내. */
const reasonOf = (e: unknown) =>
  e instanceof ApiError ? e.message : '처리됐는지 확인하지 못했어요. 목록을 새로 본 뒤 다시 시도하세요.'

/** 직원 화면. 역할마다 할 수 있는 일만 보여 주며, 실제 권한은 서버가 검사한다. */
export function AdminPage() {
  const me = useMe().data!
  if (me.role === 'CUSTOMER') return <Navigate to="/" replace />
  const role = ROLE[me.role]
  return (
    <main className={styles.page}>
      <section className={styles.summary}>
        <h1>{role.label} <span>{me.loginId}</span></h1>
        <p>{role.duty}</p>
      </section>
      {me.role === 'OPERATOR' && <Deposits />}
      {me.role === 'AUDITOR' && <AuditCheck />}
      <Adjustments canDecide={me.role === 'APPROVER'} />
    </main>
  )
}

/** 최근 입금·입고. 운영자는 여기서 정정을 요청한다. */
function Deposits() {
  const deposits = useDeposits()
  const rows = deposits.data?.pages.flatMap((page) => page.items) ?? []
  const [target, setTarget] = useState<Deposit | null>(null)
  const [reason, setReason] = useState('')
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)
  const dialog = useRef<HTMLDialogElement>(null)
  const id = useId()
  const client = useQueryClient()
  const toast = useToast()

  /** 정정 요청 창을 연다. */
  function open(deposit: Deposit) {
    setTarget(deposit)
    setReason('')
    setError('')
    dialog.current?.showModal()
  }

  /** 정정을 요청한다. 같은 전표는 한 번만 접수되므로 다시 보내도 중복되지 않는다. */
  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!target || sending) return
    setSending(true)
    setError('')
    try {
      await api.requestAdjustment(target.depositId, reason.trim())
      dialog.current?.close()
      toast('정정을 요청했어요. 승인자가 결정하면 반영돼요')
      client.invalidateQueries({ queryKey: ['adjustments'] })
    } catch (e) {
      setError(reasonOf(e))
    } finally {
      setSending(false)
    }
  }

  return (
    <section className={`panel ${styles.section}`}>
      <h2>최근 입금·입고</h2>
      {rows.length === 0 ? <p className="empty">{deposits.isError ? '입금 내역을 불러오지 못했어요.' : '입금 내역이 없어요.'}</p> : (
        <ul className={styles.rows}>
          {rows.map((row) => (
            <li key={row.depositId}>
              <span className={styles.main}>
                <b>{row.owner} · {amountOf(row)}</b>
                <small>{row.symbol ? '입고' : '입금'} #{row.depositId} · {moment(row.createdAt)}</small>
              </span>
              <button type="button" className="soft-button" onClick={() => open(row)}
                aria-label={`입금 ${row.depositId}번 정정 요청`}>정정 요청</button>
            </li>
          ))}
        </ul>
      )}
      {deposits.hasNextPage && <button type="button" className="soft-button" disabled={deposits.isFetchingNextPage} onClick={() => deposits.fetchNextPage()}>더 보기</button>}

      <dialog ref={dialog} className={styles.dialog} aria-labelledby={`${id}-title`} onCancel={(e) => sending && e.preventDefault()}>
        {target && (
          <form onSubmit={submit}>
            <h3 id={`${id}-title`}>{target.owner}의 {amountOf(target)}을<br />되돌려 달라고 요청할까요?</h3>
            <p className={styles.note}>승인되면 반대 전표가 기록되고, 원래 기록은 그대로 남아요.</p>
            <label htmlFor={`${id}-reason`}>사유</label>
            <input id={`${id}-reason`} className="field" maxLength={200} required autoComplete="off"
              placeholder="예: 같은 입금이 두 번 들어옴" value={reason} onChange={(e) => setReason(e.target.value)} />
            {error && <p className={styles.error} role="alert">{error}</p>}
            <div className={styles.actions}>
              <button type="button" className="soft-button" disabled={sending} onClick={() => dialog.current?.close()}>닫기</button>
              <button type="submit" className="solid-button" disabled={sending || reason.trim() === ''}>{sending ? '보내는 중' : '요청하기'}</button>
            </div>
          </form>
        )}
      </dialog>
    </section>
  )
}

/** 정정 요청 목록. 승인자는 대기 중인 요청을 결정한다. */
function Adjustments({ canDecide }: { canDecide: boolean }) {
  const adjustments = useAdjustments()
  const rows = adjustments.data?.pages.flatMap((page) => page.items) ?? []
  const [pending, setPending] = useState<{ row: Adjustment; approve: boolean } | null>(null)
  const [error, setError] = useState('')
  const [sending, setSending] = useState(false)
  const dialog = useRef<HTMLDialogElement>(null)
  const id = useId()
  const client = useQueryClient()
  const toast = useToast()

  /** 결정 확인 창을 연다. */
  function open(row: Adjustment, approve: boolean) {
    setPending({ row, approve })
    setError('')
    dialog.current?.showModal()
  }

  /** 승인하거나 거절한다. 이미 결정된 요청은 서버가 거절하므로 두 번 반영되지 않는다. */
  async function decide() {
    if (!pending || sending) return
    setSending(true)
    setError('')
    try {
      await api.decideAdjustment(pending.row.adjustmentId, pending.approve)
      dialog.current?.close()
      toast(pending.approve ? '승인했어요. 반대 전표를 기록했어요' : '거절했어요')
    } catch (e) {
      setError(reasonOf(e))
    } finally {
      setSending(false)
      client.invalidateQueries({ queryKey: ['adjustments'] })
    }
  }

  return (
    <section className={`panel ${styles.section}`}>
      <h2>원장 정정 요청</h2>
      {rows.length === 0 ? <p className="empty">{adjustments.isError ? '요청을 불러오지 못했어요.' : '정정 요청이 없어요.'}</p> : (
        <ul className={styles.rows}>
          {rows.map((row) => (
            <li key={row.adjustmentId}>
              <span className={styles.main}>
                <b><span className={`${styles.status} ${styles[row.status]}`}>{STATUS[row.status]}</span>{row.owner} · {amountOf(row)}</b>
                <span className={styles.reason}>{row.reason}</span>
                <small>
                  #{row.adjustmentId} · {row.requester} 요청 {moment(row.createdAt)}
                  {row.decider && row.decidedAt && ` · ${row.decider} ${STATUS[row.status]} ${moment(row.decidedAt)}`}
                </small>
              </span>
              {canDecide && row.status === 'REQUESTED' && (
                <span className={styles.buttons}>
                  <button type="button" className="soft-button" onClick={() => open(row, false)} aria-label={`정정 ${row.adjustmentId}번 거절`}>거절</button>
                  <button type="button" className="soft-button" onClick={() => open(row, true)} aria-label={`정정 ${row.adjustmentId}번 승인`}>승인</button>
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {adjustments.hasNextPage && <button type="button" className="soft-button" disabled={adjustments.isFetchingNextPage} onClick={() => adjustments.fetchNextPage()}>더 보기</button>}

      <dialog ref={dialog} className={styles.dialog} aria-labelledby={`${id}-title`} onCancel={(e) => sending && e.preventDefault()}>
        {pending && (
          <div>
            <h3 id={`${id}-title`}>{pending.row.owner}의 {amountOf(pending.row)}<br />정정을 {pending.approve ? '승인' : '거절'}할까요?</h3>
            <dl className={styles.facts}>
              <div><dt>사유</dt><dd>{pending.row.reason}</dd></div>
              <div><dt>요청자</dt><dd>{pending.row.requester}</dd></div>
            </dl>
            <p className={styles.note}>
              {pending.approve ? '고객의 잔고에서 빼고 거래내역에 정정으로 남겨요. 이미 썼거나 주문에 묶인 금액은 되돌릴 수 없어요.'
                : '잔고는 바뀌지 않아요. 운영자는 다시 요청할 수 있어요.'}
            </p>
            {error && <p className={styles.error} role="alert">{error}</p>}
            <div className={styles.actions}>
              <button type="button" className="soft-button" disabled={sending} onClick={() => dialog.current?.close()}>닫기</button>
              <button type="button" className="solid-button" disabled={sending} onClick={decide}>
                {sending ? '보내는 중' : pending.approve ? '승인하기' : '거절하기'}
              </button>
            </div>
          </div>
        )}
      </dialog>
    </section>
  )
}

/** 감사 로그 전체를 다시 계산해 기록 당시와 같은지 확인한다. */
function AuditCheck() {
  const [result, setResult] = useState<AuditVerification | null>(null)
  const [error, setError] = useState('')
  const [checking, setChecking] = useState(false)

  /** 검증을 요청한다. 기록이 많으면 시간이 걸린다. */
  async function check() {
    setChecking(true)
    setError('')
    try {
      setResult(await api.verifyAudit())
    } catch (e) {
      setResult(null)
      setError(e instanceof ApiError ? e.message : '검증하지 못했어요. 잠시 후 다시 시도하세요.')
    } finally {
      setChecking(false)
    }
  }

  return (
    <section className={`panel ${styles.section} ${styles.audit}`}>
      <h2>감사 로그 검증</h2>
      <p className={styles.note}>모든 기록의 해시를 처음부터 다시 계산하고, DB 밖에 남긴 값과 비교해요.</p>
      <div role="status">
        {result && (result.valid ? (
          <p className={styles.valid}><b>이상 없음</b>기록 {num(result.checked)}건이 기록 당시와 같고, DB 밖에 남긴 값 {num(result.anchors)}개와 일치해요.</p>
        ) : (
          <div className={styles.invalid}>
            <b>변조 흔적 발견</b>
            <ul>{result.problems.map((problem) => <li key={problem}>{problem}</li>)}</ul>
          </div>
        ))}
        {error && <p className={styles.error}>{error}</p>}
      </div>
      <button type="button" className="solid-button" disabled={checking} onClick={check}>{checking ? '검증하는 중' : '지금 검증하기'}</button>
    </section>
  )
}
