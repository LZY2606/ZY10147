const api = (path, options = {}) =>
  fetch('/api' + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  }).then(async (r) => {
    const text = await r.text();
    const body = text ? JSON.parse(text) : null;
    if (!r.ok) {
      const err = new Error((body && body.message) || r.statusText);
      err.body = body;
      throw err;
    }
    return body;
  });

function toast(msg) {
  let el = document.querySelector('.toast');
  if (!el) {
    el = document.createElement('div');
    el.className = 'toast';
    document.body.appendChild(el);
  }
  el.textContent = msg;
  el.style.display = 'block';
  clearTimeout(el._t);
  el._t = setTimeout(() => (el.style.display = 'none'), 4000);
}

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const qs = (p) => new URLSearchParams(location.search).get(p);

function renderChange(change) {
  const exception = change.exceptionRef
    ? `<div class="evidence">approved exception: <b>${esc(change.exceptionRef)}</b></div>` : '';
  const evidence = change.evidence
    ? `<div class="evidence">evidence: ${esc(change.evidence)}</div>` : '';
  const member = change.member ? ` <span class="pill">${esc(change.member)}</span>` : '';
  const fromTo = (change.from || change.to)
    ? `<div class="fromto"><span class="from">${esc(change.from)}</span> → <span class="to">${esc(change.to)}</span></div>` : '';
  return `<div class="change">
    <div class="head">
      <span class="badge ${esc(change.severity)}">${esc(change.severity)}</span>
      <span class="kind">${esc(change.kind)}</span>${member}
    </div>
    <div class="body">
      <div class="detail">${esc(change.detail || '')}</div>
      ${fromTo}${evidence}${exception}
    </div>
  </div>`;
}

function renderDecisions(decisions) {
  if (!decisions || !decisions.length) return '';
  const items = decisions.map((d) => `
    <div>
      <span class="badge ${d.verdict === 'ACCEPT' ? 'COMPATIBLE' : 'BREAKING'}">${esc(d.verdict)}</span>
      platform <b>${esc(d.platform)}</b>
      <span class="status-${esc(d.status)}">${esc(d.status)}</span>
      <span class="muted">${esc(d.effectiveFrom)} → ${esc(d.expiresAfterVersion || '∞')}</span>
      <div class="muted">${esc(d.rationale || '')}</div>
    </div>`).join('');
  return `<div class="decisions"><b>Decisions</b>${items}</div>`;
}
