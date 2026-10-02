import { fetchEngagementDetail, findDirectionOutput, submitRegeneration, pollRegeneration }
  from '/agent-views/_shared/regenerate.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export async function mount(container, agent, ctx) {
  container.className = 'av-view';
  container.innerHTML = '<div class="av-value">Loading current playbook…</div>';

  let detail, dir;
  try {
    detail = await fetchEngagementDetail(agent.engagementId);
    dir = findDirectionOutput(detail, agent.direction);
  } catch (err) {
    container.innerHTML = `<div class="av-error">Could not load current output: ${esc(err.message)}</div>`;
    return;
  }
  if (!dir || !dir.playbook) {
    container.innerHTML = '<div class="av-value">No playbook generated yet for this direction.</div>';
    return;
  }

  render(container, agent, ctx, dir.playbook);
}

function render(container, agent, ctx, playbook) {
  const applications = (playbook.recommendedApplications || []).map(a => `<li>${esc(a)}</li>`).join('');

  container.innerHTML = `
    <div class="av-section"><div class="av-label">Strategic Summary</div><div class="av-value">${esc(playbook.strategicSummary)}</div></div>
    <div class="av-section"><div class="av-label">Brand Foundations</div><div class="av-value">${esc(playbook.brandFoundations)}</div></div>
    <div class="av-section"><div class="av-label">Verbal Identity</div><div class="av-value">${esc(playbook.verbalIdentity)}</div></div>
    <div class="av-section"><div class="av-label">Recommended Applications</div><ul class="av-list">${applications || '<li>None listed.</li>'}</ul></div>
    <div class="av-cascade">Regenerating your playbook will also update your Brand Book.</div>
    <div class="av-section">
      <div class="av-label">Feedback for this regeneration (optional)</div>
      <textarea class="av-feedback" placeholder="e.g. Tighten the strategic summary…"></textarea>
    </div>
    <div class="av-footer">
      <button class="av-submit" type="button">Regenerate</button>
      <span class="av-status"></span>
    </div>
  `;
  wireSubmit(container, agent, ctx);
}

function wireSubmit(container, agent, ctx) {
  const button = container.querySelector('.av-submit');
  const status = container.querySelector('.av-status');
  const feedback = container.querySelector('.av-feedback');

  button.onclick = async () => {
    button.disabled = true;
    status.textContent = 'Queuing…';
    container.querySelector('.av-error')?.remove();
    try {
      const job = await submitRegeneration(agent.engagementId, agent.agentId, agent.direction, feedback.value.trim());
      await pollRegeneration(agent.engagementId, job.id, (j) => {
        status.textContent = j.status === 'RUNNING' ? 'Regenerating…' : 'Queued…';
      });
      status.textContent = 'Done.';
      ctx.onAssetGenerated?.();
      const detail = await fetchEngagementDetail(agent.engagementId);
      const dir = findDirectionOutput(detail, agent.direction);
      render(container, agent, ctx, dir.playbook);
    } catch (err) {
      button.disabled = false;
      status.textContent = '';
      const errEl = document.createElement('div');
      errEl.className = 'av-error';
      errEl.textContent = err.message || 'Regeneration failed.';
      container.querySelector('.av-footer').after(errEl);
    }
  };
}
