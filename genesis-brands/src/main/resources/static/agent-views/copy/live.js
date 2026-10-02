import { fetchEngagementDetail, findDirectionOutput, submitRegeneration, pollRegeneration }
  from '/agent-views/_shared/regenerate.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export async function mount(container, agent, ctx) {
  container.className = 'av-view';
  container.innerHTML = '<div class="av-value">Loading current copy…</div>';

  let detail, dir;
  try {
    detail = await fetchEngagementDetail(agent.engagementId);
    dir = findDirectionOutput(detail, agent.direction);
  } catch (err) {
    container.innerHTML = `<div class="av-error">Could not load current output: ${esc(err.message)}</div>`;
    return;
  }
  if (!dir || !dir.copy) {
    container.innerHTML = '<div class="av-value">No copy generated yet for this direction.</div>';
    return;
  }

  render(container, agent, ctx, dir.copy);
}

function render(container, agent, ctx, copy) {
  container.innerHTML = `
    <div class="av-section"><div class="av-label">Tagline</div><div class="av-value">${esc(copy.tagline)}</div></div>
    <div class="av-section"><div class="av-label">Mission</div><div class="av-value">${esc(copy.missionStatement)}</div></div>
    <div class="av-section"><div class="av-label">Brand Story</div><div class="av-value">${esc(copy.brandStory)}</div></div>
    <div class="av-section"><div class="av-label">Elevator Pitch</div><div class="av-value">${esc(copy.elevatorPitch)}</div></div>
    <div class="av-cascade">Regenerating your copy will also update your Playbook and Brand Book.</div>
    <div class="av-section">
      <div class="av-label">Feedback for this regeneration (optional)</div>
      <textarea class="av-feedback" placeholder="e.g. Make the tagline punchier, less formal tone…"></textarea>
    </div>
    <div class="av-footer">
      <button class="av-submit" type="button">Regenerate</button>
      <span class="av-status"></span>
    </div>
  `;
  wireSubmit(container, agent, ctx, 'copy');
}

function wireSubmit(container, agent, ctx, field) {
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
      render(container, agent, ctx, dir[field]);
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
