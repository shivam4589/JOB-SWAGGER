import { useCallback, useEffect, useMemo, useState } from 'react'

const STATUSES = ['PENDING', 'RUNNING', 'SUCCEEDED', 'DEAD', 'FAILED', 'CANCELLED']

async function request(url, options) {
  const response = await fetch(url, options)
  if (!response.ok) {
    let message = `Request failed (${response.status})`
    try {
      const body = await response.json()
      message = body.message || body.error || message
    } catch {
      // Keep the HTTP status message when the response is not JSON.
    }
    throw new Error(message)
  }
  return response.json()
}

function formatDate(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString()
}

function relativeTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const seconds = Math.round((date.getTime() - Date.now()) / 1000)
  const formatter = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
  if (Math.abs(seconds) < 60) return formatter.format(seconds, 'second')
  if (Math.abs(seconds) < 3600) return formatter.format(Math.round(seconds / 60), 'minute')
  if (Math.abs(seconds) < 86400) return formatter.format(Math.round(seconds / 3600), 'hour')
  return formatter.format(Math.round(seconds / 86400), 'day')
}

function shortId(id) {
  return id ? id.slice(0, 8) : '—'
}

function StatusBadge({ status }) {
  return <span className={`status-badge status-${(status || 'unknown').toLowerCase()}`}>{status || 'UNKNOWN'}</span>
}

function Icon({ name, size = 18 }) {
  const shared = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round',
    strokeLinejoin: 'round',
    'aria-hidden': true,
  }
  const paths = {
    grid: <><rect x="3.5" y="3.5" width="7" height="7" rx="1.5" /><rect x="13.5" y="3.5" width="7" height="7" rx="1.5" /><rect x="3.5" y="13.5" width="7" height="7" rx="1.5" /><rect x="13.5" y="13.5" width="7" height="7" rx="1.5" /></>,
    layers: <><path d="m12 3 9 5-9 5-9-5 9-5Z" /><path d="m3 12 9 5 9-5" /><path d="m3 16 9 5 9-5" /></>,
    search: <><circle cx="10.8" cy="10.8" r="6.8" /><path d="m16 16 4.5 4.5" /></>,
    plus: <><path d="M12 5v14" /><path d="M5 12h14" /></>,
    refresh: <><path d="M20 7v5h-5" /><path d="M4.9 9a7.5 7.5 0 0 1 12.7-2L20 12" /><path d="M4 17v-5h5" /><path d="M19.1 15a7.5 7.5 0 0 1-12.7 2L4 12" /></>,
    close: <><path d="m18 6-12 12" /><path d="m6 6 12 12" /></>,
    chevron: <path d="m9 18 6-6-6-6" />,
    clock: <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /></>,
    check: <><path d="m5 12 4 4L19 6" /></>,
    activity: <><path d="M3 12h4l3-8 4 16 3-8h4" /></>,
    alert: <><path d="M10.3 3.9 2.7 17a2 2 0 0 0 1.7 3h15.2a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z" /><path d="M12 9v4" /><path d="M12 17h.01" /></>,
    sliders: <><path d="M4 21v-7" /><path d="M4 10V3" /><path d="M12 21v-9" /><path d="M12 8V3" /><path d="M20 21v-5" /><path d="M20 12V3" /><path d="M2 14h4" /><path d="M10 8h4" /><path d="M18 16h4" /></>,
  }
  return <svg {...shared}>{paths[name]}</svg>
}

function MetricCard({ label, count, icon, tone, loading }) {
  return (
    <div className="metric-card">
      <div className={`metric-icon ${tone}`}><Icon name={icon} size={19} /></div>
      <div className="metric-copy">
        <span>{label}</span>
        <strong>{loading ? '—' : count}</strong>
      </div>
      <span className="metric-caption">recent jobs</span>
    </div>
  )
}

function JobTable({ jobs, loading, onSelect, onClear }) {
  if (loading && jobs.length === 0) {
    return <div className="table-message"><span className="spinner" /> Loading jobs…</div>
  }
  if (!loading && jobs.length === 0) {
    return (
      <div className="empty-state">
        <div className="empty-icon"><Icon name="layers" size={25} /></div>
        <strong>No jobs found</strong>
        <p>Try another filter or submit a new job to get started.</p>
        <button className="text-button" onClick={onClear}>Clear filters</button>
      </div>
    )
  }
  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr>
            <th>Job</th>
            <th>Status</th>
            <th>Priority</th>
            <th>Attempts</th>
            <th>Created</th>
            <th aria-label="Open job" />
          </tr>
        </thead>
        <tbody>
          {jobs.map((job) => (
            <tr key={job.id} onClick={() => onSelect(job)} tabIndex={0} onKeyDown={(event) => {
              if (event.key === 'Enter') onSelect(job)
            }}>
              <td>
                <div className="job-cell">
                  <span className="job-type">{job.type}</span>
                  <span className="job-id">#{shortId(job.id)}</span>
                </div>
              </td>
              <td><StatusBadge status={job.status} /></td>
              <td><span className="priority"><span className={`priority-dot priority-${job.priority >= 8 ? 'high' : job.priority >= 5 ? 'normal' : 'low'}`} />{job.priority ?? '—'}</span></td>
              <td><span className="attempt-count">{job.attempts ?? 0}<span> / {job.maxAttempts ?? '—'}</span></span></td>
              <td><span className="created-time">{relativeTime(job.createdAt)}</span></td>
              <td><button className="row-open" aria-label={`View job ${shortId(job.id)}`}><Icon name="chevron" size={17} /></button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function SubmitModal({ onClose, onSubmitted }) {
  const [type, setType] = useState('SEND_EMAIL')
  const [payload, setPayload] = useState('{\n  "to": "user@example.com"\n}')
  const [priority, setPriority] = useState('5')
  const [delaySeconds, setDelaySeconds] = useState('0')
  const [maxAttempts, setMaxAttempts] = useState('5')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  function changeType(value) {
    setType(value)
    if (value === 'SLOW') setPayload('{\n  "seconds": 20\n}')
    else if (value === 'SEND_EMAIL') setPayload('{\n  "to": "user@example.com"\n}')
    else setPayload('{}')
  }

  async function submit(event) {
    event.preventDefault()
    setError('')
    let parsedPayload
    try {
      parsedPayload = JSON.parse(payload)
    } catch {
      setError('Payload must be valid JSON.')
      return
    }
    if (parsedPayload === null) {
      setError('Payload cannot be null.')
      return
    }

    setSubmitting(true)
    try {
      const job = await request('/api/jobs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          type: type.trim(),
          payload: parsedPayload,
          priority: Number(priority),
          delaySeconds: Number(delaySeconds),
          maxAttempts: Number(maxAttempts),
        }),
      })
      onSubmitted(job)
    } catch (submissionError) {
      setError(submissionError.message)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="modal-backdrop" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <section className="modal" role="dialog" aria-modal="true" aria-labelledby="submit-title">
        <div className="modal-header">
          <div><span className="eyebrow">QUEUE ACTION</span><h2 id="submit-title">Create a job</h2></div>
          <button className="icon-button" onClick={onClose} aria-label="Close dialog"><Icon name="close" /></button>
        </div>
        <p className="modal-intro">Add a task to the queue. Your worker will pick it up when it’s ready.</p>
        <form onSubmit={submit}>
          <label className="field-label" htmlFor="job-type">Job type</label>
          <input id="job-type" className="input-control" value={type} onChange={(event) => setType(event.target.value)} required maxLength={100} placeholder="e.g. SEND_EMAIL" />
          <div className="type-suggestions">
            {['SEND_EMAIL', 'NOOP', 'SLOW', 'ALWAYS_FAIL'].map((suggestion) => (
              <button key={suggestion} type="button" onClick={() => changeType(suggestion)} className={type === suggestion ? 'suggestion active' : 'suggestion'}>{suggestion}</button>
            ))}
          </div>

          <label className="field-label payload-label" htmlFor="job-payload">Payload <span>JSON</span></label>
          <textarea id="job-payload" className="input-control code-input" value={payload} onChange={(event) => setPayload(event.target.value)} rows={5} spellCheck={false} />

          <div className="form-grid">
            <div><label className="field-label" htmlFor="job-priority">Priority <span>1–10</span></label><input id="job-priority" className="input-control" type="number" min="1" max="10" value={priority} onChange={(event) => setPriority(event.target.value)} required /></div>
            <div><label className="field-label" htmlFor="job-max-attempts">Max attempts <span>1–20</span></label><input id="job-max-attempts" className="input-control" type="number" min="1" max="20" value={maxAttempts} onChange={(event) => setMaxAttempts(event.target.value)} required /></div>
          </div>
          <label className="field-label delay-label" htmlFor="job-delay">Delay <span>seconds before the job can run</span></label>
          <input id="job-delay" className="input-control" type="number" min="0" value={delaySeconds} onChange={(event) => setDelaySeconds(event.target.value)} required />

          {error && <div className="form-error" role="alert"><Icon name="alert" size={16} />{error}</div>}
          <div className="modal-actions">
            <button type="button" className="button button-secondary" onClick={onClose}>Cancel</button>
            <button type="submit" className="button button-primary" disabled={submitting}>{submitting ? <><span className="spinner spinner-white" />Adding job…</> : <><Icon name="plus" size={17} />Add to queue</>}</button>
          </div>
        </form>
      </section>
    </div>
  )
}

function JobDrawer({ job, attempts, attemptsLoading, attemptsError, onClose }) {
  return (
    <div className="drawer-backdrop" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <aside className="job-drawer" role="dialog" aria-modal="true" aria-labelledby="drawer-title">
        <div className="drawer-header">
          <div><span className="eyebrow">JOB DETAILS</span><h2 id="drawer-title">{job.type}</h2></div>
          <button className="icon-button" onClick={onClose} aria-label="Close job details"><Icon name="close" /></button>
        </div>
        <div className="drawer-summary"><StatusBadge status={job.status} /><span>#{job.id}</span></div>
        {job.lastError && <div className="last-error"><Icon name="alert" size={17} /><div><strong>Last error</strong><p>{job.lastError}</p></div></div>}
        <div className="detail-grid">
          <div><span>Priority</span><strong>{job.priority ?? '—'}</strong></div>
          <div><span>Attempts</span><strong>{job.attempts ?? 0} / {job.maxAttempts ?? '—'}</strong></div>
          <div><span>Created</span><strong>{formatDate(job.createdAt)}</strong></div>
          <div><span>Updated</span><strong>{formatDate(job.updatedAt)}</strong></div>
          <div><span>Scheduled</span><strong>{formatDate(job.runAt)}</strong></div>
        </div>
        <div className="drawer-section">
          <div className="section-title"><h3>Payload</h3><span>JSON</span></div>
          <pre className="json-block">{JSON.stringify(job.payload, null, 2) ?? 'null'}</pre>
        </div>
        <div className="drawer-section attempts-section">
          <div className="section-title"><h3>Attempts</h3><span>{attemptsLoading ? 'Loading…' : attempts.length}</span></div>
          {attemptsError && <div className="inline-error" role="alert">{attemptsError}</div>}
          {!attemptsLoading && !attemptsError && attempts.length === 0 && <p className="no-attempts">This job hasn’t been picked up by a worker yet.</p>}
          {attempts.map((attempt) => (
            <div className="attempt-row" key={attempt.id}>
              <span className={`attempt-icon ${attempt.success === true ? 'attempt-success' : attempt.success === false ? 'attempt-failure' : 'attempt-running'}`}><Icon name={attempt.success === true ? 'check' : attempt.success === false ? 'close' : 'clock'} size={15} /></span>
              <div className="attempt-info">
                <div><strong>Attempt {attempt.id}</strong><span>{attempt.success === true ? 'Succeeded' : attempt.success === false ? 'Failed' : 'Running'}</span></div>
                <small>{attempt.workerId || 'Worker'} · {formatDate(attempt.startedAt)}</small>
                {attempt.error && <p>{attempt.error}</p>}
              </div>
            </div>
          ))}
        </div>
      </aside>
    </div>
  )
}

export default function App() {
  const [jobs, setJobs] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState('ALL')
  const [search, setSearch] = useState('')
  const [lastUpdated, setLastUpdated] = useState(null)
  const [submitting, setSubmitting] = useState(false)
  const [selectedJob, setSelectedJob] = useState(null)
  const [attempts, setAttempts] = useState([])
  const [attemptsLoading, setAttemptsLoading] = useState(false)
  const [attemptsError, setAttemptsError] = useState('')

  const loadJobs = useCallback(async (initial = false) => {
    if (initial) setLoading(true)
    try {
      const result = await request('/api/jobs?limit=200')
      setJobs(result)
      setError('')
      setLastUpdated(new Date())
    } catch (loadError) {
      setError(loadError.message)
    } finally {
      if (initial) setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadJobs(true)
    const interval = window.setInterval(() => loadJobs(), 5000)
    return () => window.clearInterval(interval)
  }, [loadJobs])

  const counts = useMemo(() => ({
    total: jobs.length,
    pending: jobs.filter((job) => job.status === 'PENDING').length,
    running: jobs.filter((job) => job.status === 'RUNNING').length,
    succeeded: jobs.filter((job) => job.status === 'SUCCEEDED').length,
    dead: jobs.filter((job) => job.status === 'DEAD' || job.status === 'FAILED').length,
  }), [jobs])

  const visibleJobs = useMemo(() => {
    const query = search.trim().toLowerCase()
    return jobs.filter((job) => {
      const statusMatches = filter === 'ALL' || job.status === filter
      const searchMatches = !query || `${job.type} ${job.id} ${job.status}`.toLowerCase().includes(query)
      return statusMatches && searchMatches
    })
  }, [jobs, filter, search])

  async function openJob(job) {
    setSelectedJob(job)
    setAttempts([])
    setAttemptsError('')
    setAttemptsLoading(true)
    try {
      setAttempts(await request(`/api/jobs/${job.id}/attempts`))
    } catch (loadError) {
      setAttemptsError(loadError.message)
    } finally {
      setAttemptsLoading(false)
    }
  }

  function handleSubmitted(job) {
    setSubmitting(false)
    setSelectedJob(job)
    loadJobs()
  }

  function clearFilters() {
    setFilter('ALL')
    setSearch('')
  }

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a className="brand" href="#" aria-label="Queueboard home">
          <span className="brand-mark"><span /><span /><span /></span>
          <span>queue<span className="brand-light">board</span></span>
        </a>
        <div className="workspace-switcher">
          <span className="workspace-icon">JQ</span>
          <span className="workspace-copy"><strong>Job Queue</strong><small>Workspace</small></span>
          <span className="workspace-chevron">⌄</span>
        </div>
        <div className="nav-label">WORKSPACE</div>
        <nav className="side-nav" aria-label="Main navigation">
          <button className="nav-item active"><Icon name="grid" size={18} />Overview</button>
          <button className="nav-item" onClick={() => document.getElementById('jobs-section')?.scrollIntoView({ behavior: 'smooth' })}><Icon name="layers" size={18} />Jobs<span className="nav-count">{counts.total}</span></button>
        </nav>
        <div className="sidebar-bottom">
          <div className="api-status"><span className={`connection-dot ${error ? 'offline' : ''}`} /><span><strong>{loading ? 'Connecting…' : error ? 'API unavailable' : 'API connected'}</strong><small>{error ? 'Check your connection' : 'localhost:8081'}</small></span></div>
          <div className="user-card"><span className="avatar">JD</span><span><strong>Job Dashboard</strong><small>Local environment</small></span><span className="user-menu">···</span></div>
        </div>
      </aside>

      <main className="main-content">
        <header className="topbar">
          <div className="breadcrumbs"><span>Job Queue</span><Icon name="chevron" size={14} /><strong>Overview</strong></div>
          <div className="topbar-right"><span className="live-indicator"><span />Live updates</span><button className="icon-button refresh-button" onClick={() => loadJobs()} aria-label="Refresh jobs"><Icon name="refresh" /></button></div>
        </header>

        <div className="page-content">
          <section className="page-heading">
            <div><span className="eyebrow">MONITORING</span><h1>Job overview</h1><p>Track and manage the work flowing through your queue.</p></div>
            <button className="button button-primary new-job-button" onClick={() => setSubmitting(true)}><Icon name="plus" size={18} />Create job</button>
          </section>

          {error && <div className="connection-error" role="alert"><Icon name="alert" size={19} /><div><strong>Couldn’t load jobs</strong><span>{error} · Check that the API is running on port 8081.</span></div><button className="text-button" onClick={() => loadJobs(true)}>Try again</button></div>}

          <section className="metrics-grid" aria-label="Job metrics">
            <MetricCard label="Total jobs" count={counts.total} icon="layers" tone="metric-purple" loading={loading} />
            <MetricCard label="Pending" count={counts.pending} icon="clock" tone="metric-amber" loading={loading} />
            <MetricCard label="Running" count={counts.running} icon="activity" tone="metric-blue" loading={loading} />
            <MetricCard label="Succeeded" count={counts.succeeded} icon="check" tone="metric-green" loading={loading} />
            <MetricCard label="Dead letters" count={counts.dead} icon="alert" tone="metric-red" loading={loading} />
          </section>

          <section className="jobs-panel" id="jobs-section">
            <div className="panel-heading">
              <div><div className="panel-title"><h2>Recent jobs</h2><span className="result-count">{counts.total}</span></div><p>Latest activity across your queue <span>· Up to 200 jobs</span></p></div>
              <span className="updated-time">{lastUpdated ? `Updated ${relativeTime(lastUpdated)}` : 'Waiting for data'}</span>
            </div>
            <div className="table-toolbar">
              <label className="search-field"><Icon name="search" size={17} /><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search jobs…" aria-label="Search jobs" />{search && <button onClick={() => setSearch('')} aria-label="Clear search"><Icon name="close" size={14} /></button>}</label>
              <label className="filter-field"><Icon name="sliders" size={16} /><select value={filter} onChange={(event) => setFilter(event.target.value)} aria-label="Filter by status"><option value="ALL">All statuses</option>{STATUSES.map((status) => <option key={status} value={status}>{status.replace('_', ' ')}</option>)}</select></label>
            </div>
            <JobTable jobs={visibleJobs} loading={loading} onSelect={openJob} onClear={clearFilters} />
            <div className="table-footer"><span>Showing <strong>{visibleJobs.length}</strong> of <strong>{counts.total}</strong> recent jobs</span><span className="auto-refresh"><span />Auto-refreshes every 5 seconds</span></div>
          </section>
          <footer className="page-footer"><span>Queueboard <span>·</span> Job Queue Console</span><span>{lastUpdated ? `Last synced ${formatDate(lastUpdated)}` : 'Connecting to API…'}</span></footer>
        </div>
      </main>

      {submitting && <SubmitModal onClose={() => setSubmitting(false)} onSubmitted={handleSubmitted} />}
      {selectedJob && <JobDrawer job={selectedJob} attempts={attempts} attemptsLoading={attemptsLoading} attemptsError={attemptsError} onClose={() => setSelectedJob(null)} />}
    </div>
  )
}
