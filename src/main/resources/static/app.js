'use strict';
const $ = (s, el=document) => el.querySelector(s);
const $$ = (s, el=document) => [...el.querySelectorAll(s)];
const api = async (path, opts) => {
  const r = await fetch('/api' + path, opts ? {headers:{'Content-Type':'application/json'}, ...opts} : undefined);
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw Object.assign(new Error(body.error || r.statusText), {status:r.status, body});
  return body;
};
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));

const state = { snapshots: [], rulesets: [], comparison: null };

// ---------- tabs ----------
$$('nav a').forEach(a => a.addEventListener('click', e => {
  e.preventDefault();
  $$('nav a').forEach(x => x.classList.remove('active'));
  a.classList.add('active');
  $$('.tab').forEach(t => t.classList.add('hidden'));
  $('#tab-' + a.dataset.tab).classList.remove('hidden');
  if (a.dataset.tab === 'snapshots') loadSnapshotsTab();
  if (a.dataset.tab === 'rules') loadRules();
  if (a.dataset.tab === 'graph') loadComparisonsForGraph();
}));

// ---------- data loading ----------
async function boot() {
  state.snapshots = await api('/snapshots');
  state.rulesets = await api('/rulesets');
  populateSnapshotSelects();
  populateRulesets();
}

function opt(s) {
  return `<option value="${esc(s.id)}">${esc(s.dist)} ${esc(s.version)} · ${esc(s.platform)}/${esc(s.arch)} · ${esc(s.content_hash.slice(0,10))}</option>`;
}
function populateSnapshotSelects() {
  const byPlatform = groupBy(state.snapshots, s => s.platform);
  for (const sel of ['#leftSnap', '#rightSnap']) {
    $(sel).innerHTML = state.snapshots.map(opt).join('');
  }
  // preselect linux 1.0.0 -> 1.1.0
  const l = state.snapshots.find(s => s.platform==='linux' && s.version==='1.0.0');
  const r = state.snapshots.find(s => s.platform==='linux' && s.version==='1.1.0');
  if (l) $('#leftSnap').value = l.id;
  if (r) $('#rightSnap').value = r.id;
}
function populateRulesets() {
  $('#rulesetSel').innerHTML = state.rulesets
    .map(r => `<option value="${esc(r.id)}">${esc(r.platform)}/${esc(r.arch)} rev${r.revision}${r.active?' (active)':''} — ${esc(r.note||'')}</option>`).join('');
}
function groupBy(xs, f){const out=new Map();xs.forEach(x=>{const k=f(x);if(!out.has(k))out.set(k,[]);out.get(k).push(x);});return out;}

// when left snapshot changes, restrict right to same platform/arch
$('#leftSnap').addEventListener('change', () => {
  const l = state.snapshots.find(s => s.id === $('#leftSnap').value);
  const compatible = state.snapshots.filter(s => s.platform===l.platform && s.arch===l.arch && s.id!==l.id);
  const r0 = compatible.find(s => s.version !== l.version);
  if (r0) $('#rightSnap').value = r0.id;
  syncRulesetTo(l);
});
function syncRulesetTo(snap) {
  const match = state.rulesets.find(r => r.platform===snap.platform && r.arch===snap.arch && r.active)
             || state.rulesets.find(r => r.platform===snap.platform && r.arch===snap.arch);
  if (match) $('#rulesetSel').value = match.id;
}

// ---------- compare ----------
$('#compareBtn').addEventListener('click', () => runCompare(false));
$('#demoExBtn').addEventListener('click', () => runCompare(true));

async function runCompare(seedEx) {
  const payload = {
    leftSnapshotId: $('#leftSnap').value,
    rightSnapshotId: $('#rightSnap').value,
    rulesetId: $('#rulesetSel').value,
    seedFixtureDecision: seedEx
  };
  const c = await api('/comparisons', {method:'POST', body: JSON.stringify(payload)});
  state.comparison = c;
  renderComparison(c);
  $('#graphCmp').innerHTML = `<option value="${esc(c.id)}">${esc(c.id)}</option>`;
}

function sevClass(f){return f.effectiveSeverity || f.severity;}

function renderComparison(c) {
  $('#cmpMeta').textContent =
    `${c.platform}/${c.arch} · ruleset ${c.rulesetId} · revision ${c.revision} · ${c.id}`;
  const s = c.summary;
  $('#summary').innerHTML = Object.entries(s.bySeverity)
    .map(([k,v]) => `<span class="chip ${k}">${k}: ${v}</span>`).join('')
    + `<span class="chip">total: ${s.total}</span>`;

  // platform columns: same finding set (comparisons are platform-scoped),
  // but each platform may hold a different decision. Show exception/decision
  // presence per platform.
  for (const plat of ['linux','windows','macos']) {
    const col = $('#col-' + plat);
    if (plat !== c.platform) {
      col.innerHTML = `<div class="meta">该比较属于 ${c.platform}/${c.arch}；其他平台请选择对应快照比较，决议可分别保存。</div>`;
      continue;
    }
    const rows = c.findings
      .filter(f => f.severity === 'BREAKING' || f.effectiveSeverity === 'ALLOWED')
      .map(f => {
        const d = (f.decisions||[]).find(x => x.platform===plat || x.platform==='*');
        const label = d ? `${d.decision}${d.expiredAt?' (已提前失效)':''}` : '无决议';
        return `<div class="line"><span title="${esc(f.kind)}">${esc(f.symbolStableId||'')}</span>
                <span class="${f.effectiveSeverity==='ALLOWED'?'exc':'expired'}">${label} · ${f.effectiveSeverity}</span></div>`;
      }).join('');
    col.innerHTML = rows || '<div class="meta">无破坏性变化</div>';
  }

  const byComp = new Map();
  c.findings.forEach(f => {
    const k = f.componentStableId || '(unknown)';
    if (!byComp.has(k)) byComp.set(k, []);
    byComp.get(k).push(f);
  });
  const catLabel = {ADDED:'新增',REMOVED:'删除',RENAMED:'改名',LAYOUT:'布局',SIGNATURE:'签名/调用约定',VISIBILITY:'可见性',GRAPH:'图'};
  let html = '';
  for (const [comp, fs] of byComp) {
    html += `<div class="component"><header><b>${esc(comp)}</b> <span class="meta">${fs.length} 项</span></header>`;
    for (const f of fs) {
      const sev = sevClass(f);
      const ex = f.exception;
      const excHtml = ex
        ? `<div class="exc">例外：${esc(ex.reason||'')} · scope ${esc(ex.scopeFromVersion)}..${esc(ex.scopeToVersion)} · expires ${esc(ex.expiresAtVersion)} · ruleset ${esc(ex.rulesetId)}</div>`
        : '';
      const roots = (f.affectedRoots||[]).length
        ? `<div class="roots">受影响公开入口：<b>${f.affectedRoots.map(esc).join(', ')}</b></div>` : '';
      html += `<div class="finding">
        <span class="tag ${sev}">${sev}</span>
        <span><span class="cat">${catLabel[f.category]||f.category}</span><br><span class="kind">${esc(f.kind)}</span></span>
        <div>
          <div class="title">${esc(f.title)} <span class="meta">[${esc(f.boundary)}]</span></div>
          <div class="detail">${esc(JSON.stringify(f.detail))}</div>
          ${roots}${excHtml}
        </div>
        <span class="actions">
          <button data-sym="${esc(f.symbolStableId||'')}" data-kind="${esc(f.kind)}" class="decBtn">决议…</button>
        </span>
      </div>`;
    }
    html += '</div>';
  }
  $('#findings').innerHTML = html || '<div class="meta">无差异</div>';
  $$('.decBtn').forEach(b => b.addEventListener('click', () => openDialog(c, b.dataset.sym, b.dataset.kind)));
}

// ---------- decisions ----------
function openDialog(c, symbol, kind) {
  if (!symbol) return alert('该变化没有稳定符号，无法挂决议');
  $('#decSymbol').textContent = symbol;
  $('#decKind').textContent = `${kind} · 当前比较 revision=${c.revision} · ruleset=${c.rulesetId}`;
  $('#decRev').value = c.revision;
  $('#decPlatform').value = c.platform;
  $('#decResult').textContent = '';
  $('#decDialog').showModal();
}
$('#decDecision').addEventListener('change', e => {
  $('#scopeBox').style.display = e.target.value === 'EXCEPTION' ? 'flex' : 'none';
});
$('#decForm').addEventListener('submit', async e => {
  // only act on the save button
  const submitter = e.submitter && e.submitter.value;
  if (submitter !== 'save') return;
  e.preventDefault();
  const c = state.comparison;
  const body = {
    comparisonId: c.id,
    symbolStableId: $('#decSymbol').textContent,
    platform: $('#decPlatform').value,
    decision: $('#decDecision').value,
    reason: $('#decReason').value,
    actor: $('#decActor').value,
    rulesetId: c.rulesetId,
    baseComparisonRev: Number($('#decRev').value)
  };
  if (body.decision === 'EXCEPTION') {
    body.scopeFromVersion = $('#decFrom').value;
    body.scopeToVersion = $('#decTo').value;
    body.expiresAtVersion = $('#decExpires').value;
  }
  try {
    const res = await api('/decisions', {method:'POST', body: JSON.stringify(body)});
    $('#decResult').textContent = '已提交，newRevision=' + res.newRevision;
    setTimeout(() => $('#decDialog').close(), 700);
    const fresh = await api('/comparisons/' + c.id);
    state.comparison = fresh;
    renderComparison(fresh);
  } catch (err) {
    if (err.status === 409) {
      $('#decResult').textContent =
        '符号级冲突（基础版本过期）：\n' + JSON.stringify(err.body, null, 2);
    } else {
      $('#decResult').textContent = '错误：' + err.message;
    }
  }
});

// ---------- graph ----------
async function loadComparisonsForGraph() {
  const list = await api('/comparisons');
  $('#graphCmp').innerHTML = list.map(c =>
    `<option value="${esc(c.id)}">${esc(c.id)} · ${esc(c.platform)}/${esc(c.arch)} (${c.breaking} breaking)</option>`).join('');
}
$('#graphBtn').addEventListener('click', async () => {
  const id = $('#graphCmp').value;
  if (id) drawGraph(await api('/comparisons/' + id + '/graph'));
});

// Minimal force-directed layout in vanilla JS/SVG (no external CDN).
function drawGraph(g) {
  const svg = $('#graphSvg');
  const W = svg.clientWidth || 1100, H = 640;
  svg.innerHTML = `<defs><marker id="arrow" viewBox="0 0 10 10" refX="18" refY="5"
      markerWidth="8" markerHeight="8" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="#3a4252"/></marker></defs>`;
  const changed = new Set(g.findings.filter(f => f.severity==='BREAKING' || f.effectiveSeverity==='ALLOWED').map(f => f.symbol));
  const entries = new Set();
  g.nodes.filter(n => n.boundary==='PUBLIC' && (n.kind==='FUNCTION'||n.kind==='VARIABLE')
      && n.visibility!=='HIDDEN' && n.visibility!=='STATIC_LOCAL').forEach(n => entries.add(n.id));
  const hotTargets = new Set();
  g.findings.forEach(f => (f.affectedRoots||[]).forEach(r => hotTargets.add(r)));

  const ids = new Set(g.nodes.map(n => n.id));
  const edges = g.edges.filter(e => ids.has(e.from) && ids.has(e.to));
  const nodes = g.nodes.map((n,i) => ({
    ...n,
    x: W/2 + Math.cos(i*2.399)*220*(0.5+ (i%7)/7),
    y: H/2 + Math.sin(i*1.7)*200,
    vx:0, vy:0
  }));
  const byId = new Map(nodes.map(n => [n.id,n]));

  // highlight edges on paths from changed symbols to entries via BFS reverse
  const incoming = new Map();
  edges.forEach(e => { if(!incoming.has(e.to)) incoming.set(e.to,[]); incoming.get(e.to).push(e.from); });
  const hotEdges = new Set();
  for (const f of g.findings) {
    const start = f.symbol; if (!start) continue;
    const q=[[start,null]]; const seen=new Set();
    while(q.length){
      const [cur,prev]=q.shift();
      if(!seen.add(cur)) continue;
      if(prev) hotEdges.add(prev+'->'+cur);
      const node = byId.get(cur);
      if(node && entries.has(cur.id) && cur.id!==start) continue;
      (incoming.get(cur)||[]).forEach(p => q.push([p,cur]));
    }
  }

  const edgeEls = edges.map(e => {
    const line = document.createElementNS('http://www.w3.org/2000/svg','line');
    line.setAttribute('class','edge' + (hotEdges.has(e.from+'->'+e.to)?' hot':''));
    svg.appendChild(line);
    return {e, line};
  });
  const nodeEls = nodes.map(n => {
    const gEl = document.createElementNS('http://www.w3.org/2000/svg','g');
    gEl.setAttribute('class',`node ${n.boundary.toLowerCase()} ${entries.has(n.id)?'entry':''} ${changed.has(n.id)?'changed':''}`);
    const c = document.createElementNS('http://www.w3.org/2000/svg','circle');
    c.setAttribute('r', changed.has(n.id) ? 9 : 7);
    const fill = entries.has(n.id) ? '#23436b' : (n.boundary==='PRIVATE' ? '#2a303c' : '#2f3a4d');
    c.setAttribute('fill', fill);
    const t = document.createElementNS('http://www.w3.org/2000/svg','text');
    t.setAttribute('x',11); t.setAttribute('y',4);
    t.textContent = n.name + (changed.has(n.id)?' ⚠':'');
    t.setAttribute('fill', changed.has(n.id) ? '#ff9c9c' : '#e6e9ef');
    gEl.appendChild(c); gEl.appendChild(t);
    svg.appendChild(gEl);
    n.el = gEl;
    gEl.addEventListener('click', () => alert(`${n.name}\nstable_id=${n.id}\n${n.kind} / ${n.boundary}`));
    return gEl;
  });

  function step() {
    for (const a of nodes) {
      for (const b of nodes) {
        if (a===b) continue;
        let dx=a.x-b.x, dy=a.y-b.y; let d2=dx*dx+dy*dy+0.01;
        const f=2600/d2; const d=Math.sqrt(d2);
        a.vx+=f*dx/d; a.vy+=f*dy/d;
      }
    }
    edges.forEach(({e}) => {
      const a=byId.get(e.from), b=byId.get(e.to);
      const dx=b.x-a.x, dy=b.y-a.y, d=Math.hypot(dx,dy)||1, f=(d-120)*0.02;
      a.vx+=f*dx/d; a.vy+=f*dy/d; b.vx-=f*dx/d; b.vy-=f*dy/d;
    });
    for (const n of nodes) {
      n.vx += (W/2-n.x)*0.002; n.vy += (H/2-n.y)*0.002;
      n.vx*=0.82; n.vy*=0.82;
      n.x+=n.vx; n.y+=n.vy;
      n.x=Math.max(20,Math.min(W-20,n.x)); n.y=Math.max(20,Math.min(H-20,n.y));
    }
  }
  let frames=0;
  function frame(){
    step();
    edgeEls.forEach(({e,line}) => {
      const a=byId.get(e.from), b=byId.get(e.to);
      line.setAttribute('x1',a.x);line.setAttribute('y1',a.y);line.setAttribute('x2',b.x);line.setAttribute('y2',b.y);
    });
    nodes.forEach(n => n.el.setAttribute('transform',`translate(${n.x},${n.y})`));
    if(++frames<240) requestAnimationFrame(frame);
  }
  frame();

  $('#graphLegend').innerHTML =
    `<span><i style="background:#23436b"></i>公开入口</span>
     <span><i style="background:#2a303c"></i>私有符号</span>
     <span><i style="border:2px solid var(--breaking);background:transparent"></i>发生变化</span>
     <span><i style="background:var(--breaking)"></i>到入口的受影响路径</span>`;
}

// ---------- snapshots tab ----------
async function loadSnapshotsTab() {
  const releases = await api('/releases');
  $('#snapList').innerHTML = releases.map(r => `
    <div class="snapshot-card">
      <b>${esc(r.dist)} ${esc(r.version)}</b>
      <table><tr><th>snapshot</th><th>platform/arch</th><th>extractor</th><th>content hash</th></tr>
      ${(r.snapshots||[]).map(s => `<tr>
        <td>${esc(s.id)}</td><td>${esc(s.platform)}/${esc(s.arch)}</td>
        <td>${esc(s.extractor)} ${esc(s.extractor_version)}</td>
        <td class="kind">${esc(s.content_hash)}</td></tr>`).join('')}
      </table>
    </div>`).join('');
}
$('#importForm').addEventListener('submit', async e => {
  e.preventDefault();
  try {
    const r = await api('/snapshots', {method:'POST', body: JSON.stringify({
      dist: $('#impDist').value, version: $('#impVer').value, json: $('#impJson').value
    })});
    $('#importResult').textContent = '已导入/去重: ' + JSON.stringify(r, null, 2);
    state.snapshots = await api('/snapshots');
    populateSnapshotSelects();
  } catch (err) {
    $('#importResult').textContent = '错误: ' + err.message;
  }
});

// ---------- rules tab ----------
async function loadRules() {
  const rs = await api('/rulesets');
  $('#ruleList').innerHTML = rs.map(r => `
    <div class="snapshot-card">
      <b>${esc(r.platform)}/${esc(r.arch)} — revision ${r.revision} ${r.active?'<span class="chip COMPATIBLE">active</span>':''}</b>
      <div class="meta">${esc(r.note||'')} · id=${esc(r.id)}</div>
      <table><tr><th>boundary</th><th>change kind → severity</th></tr>
      ${Object.entries(r.rules).map(([b,m]) => `<tr><td>${b}</td>
        <td class="kind">${Object.entries(m).map(([k,v]) => `${k}→${v}`).join(', ')}</td></tr>`).join('')}
      </table>
    </div>`).join('');
}

boot().catch(e => { document.body.insertAdjacentHTML('afterbegin','<pre style="color:#ff8080">'+esc(e.message)+'</pre>'); });
