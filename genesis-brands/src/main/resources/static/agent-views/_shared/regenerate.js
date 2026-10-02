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

export function pollRegeneration(engagementId, jobId, onStatus) {
  return new Promise((resolve, reject) => {
    const tick = async () => {
      try {
        const res = await fetch(`/api/engagements/${engagementId}/agents/regenerations/${jobId}`, { headers: HEADERS });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const job = await res.json();
        onStatus?.(job);
        if (job.status === 'DONE') return resolve(job);
        if (job.status === 'FAILED') return reject(new Error(job.errorMessage || 'Regeneration failed'));
        setTimeout(tick, POLL_INTERVAL_MS);
      } catch (err) {
        reject(err);
      }
    };
    tick();
  });
}
