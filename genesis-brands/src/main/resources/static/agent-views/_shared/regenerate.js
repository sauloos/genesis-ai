/**
 * Shared POST-then-poll mechanics for the 5 specialist agents' Live Dashboard views.
 * Shared infrastructure only — each agent's live.js still owns its own current-output
 * rendering and submit UI, and calls into this for the fetch+poll loop.
 */
const HEADERS = { 'Content-Type': 'application/json', 'X-Api-Key': 'genesis-ai-key' };
const POLL_INTERVAL_MS = 5000;

export async function fetchEngagementDetail(engagementId) {
  const res = await fetch(`/api/engagements/${engagementId}`, { headers: HEADERS });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  return res.json();
}

export function findDirectionOutput(detail, direction) {
  const dirs = detail?.results?.directions || [];
  return dirs.find(d => d.direction?.toUpperCase() === direction?.toUpperCase()) || null;
}

export async function submitRegeneration(engagementId, agentId, direction, feedback) {
  const res = await fetch(`/api/engagements/${engagementId}/agents/${agentId}/regenerate`, {
    method: 'POST',
    headers: HEADERS,
    body: JSON.stringify({ direction, feedback }),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `Request failed (${res.status})`);
  }
  return res.json();
}

// AWAITING_APPROVAL is a terminal state for this poll, same as DONE — a draft always lands
// there first and waits for an explicit approve/discard (see renderApprovalGate) before an
// engagement's current output ever changes.
export function pollRegeneration(engagementId, jobId, onStatus) {
  return new Promise((resolve, reject) => {
    const tick = async () => {
      try {
        const res = await fetch(`/api/engagements/${engagementId}/agents/regenerations/${jobId}`, { headers: HEADERS });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const job = await res.json();
        onStatus?.(job);
        if (job.status === 'DONE' || job.status === 'AWAITING_APPROVAL') return resolve(job);
        if (job.status === 'FAILED') return reject(new Error(job.errorMessage || 'Regeneration failed'));
        setTimeout(tick, POLL_INTERVAL_MS);
      } catch (err) {
        reject(err);
      }
    };
    tick();
  });
}

export async function approveRegeneration(engagementId, jobId) {
  const res = await fetch(`/api/engagements/${engagementId}/agents/regenerations/${jobId}/approve`, {
    method: 'POST',
    headers: HEADERS,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `Request failed (${res.status})`);
  }
  return res.json();
}

export async function discardRegeneration(engagementId, jobId) {
  const res = await fetch(`/api/engagements/${engagementId}/agents/regenerations/${jobId}/reject`, {
    method: 'POST',
    headers: HEADERS,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `Request failed (${res.status})`);
  }
  return res.json();
}

/**
 * Renders the shared draft/approve/discard chrome once a regeneration job lands in
 * AWAITING_APPROVAL. Owns only the approval UI (banner, Approve/Discard buttons, status) —
 * the caller supplies `renderDraft(el, draft)` to render the draft DirectionOutput's own
 * field(s) using that agent's existing field markup, so the preview looks identical to the
 * "current" view it's about to replace.
 */
export function renderApprovalGate(container, { engagementId, job, renderDraft, onApprove, onDiscard }) {
  const draft = JSON.parse(job.draftOutputJson);

  container.innerHTML = `
    <div class="av-approval-banner">Draft ready for review — nothing has changed yet.</div>
    <div class="av-approval-preview"></div>
    <div class="av-approval-footer">
      <button class="av-approve" type="button">Approve</button>
      <button class="av-discard" type="button">Discard</button>
      <span class="av-approval-status"></span>
    </div>
  `;
  renderDraft(container.querySelector('.av-approval-preview'), draft);

  const approveBtn = container.querySelector('.av-approve');
  const discardBtn = container.querySelector('.av-discard');
  const status = container.querySelector('.av-approval-status');

  const disable = (v) => { approveBtn.disabled = v; discardBtn.disabled = v; };

  approveBtn.onclick = async () => {
    disable(true);
    status.textContent = 'Approving…';
    try {
      await approveRegeneration(engagementId, job.id);
      await onApprove?.();
    } catch (err) {
      disable(false);
      status.textContent = err.message || 'Approve failed.';
    }
  };

  discardBtn.onclick = async () => {
    disable(true);
    status.textContent = 'Discarding…';
    try {
      await discardRegeneration(engagementId, job.id);
      await onDiscard?.();
    } catch (err) {
      disable(false);
      status.textContent = err.message || 'Discard failed.';
    }
  };
}
