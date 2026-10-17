import { fetchEngagementDetail, findDirectionOutput, submitRegeneration, pollRegeneration, renderApprovalGate }
  from '/agent-views/_shared/regenerate.js';

function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

export async function mount(container, agent, ctx) {
  container.className = 'av-view';
  container.innerHTML = '<div class="av-value">Loading current visual identity…</div>';

  let detail, dir;
  try {
    detail = await fetchEngagementDetail(agent.engagementId);
    dir = findDirectionOutput(detail, agent.direction);
  } catch (err) {
    container.innerHTML = `<div class="av-error">Could not load current output: ${esc(err.message)}</div>`;
    return;
  }
  if (!dir || !dir.visualIdentity) {
    container.innerHTML = '<div class="av-value">No visual identity generated yet for this direction.</div>';
    return;
  }

  render(container, agent, ctx, dir.visualIdentity);
}

function renderFields(el, visual) {
  const swatches = (visual.colorPalette || []).map(c => `
    <div class="av-swatch-item">
      <div class="av-swatch" style="background:${esc(c.hex)}"></div>
      <div class="av-swatch-name">${esc(c.name)}</div>
    </div>
  `).join('');

  el.innerHTML = `
    <div class="av-section">
      <div class="av-label">Color Palette</div>
      <div class="av-swatches">${swatches || '<div class="av-value">No palette yet.</div>'}</div>
    </div>
    <div class="av-section">
      <div class="av-label">Typography</div>
      <div class="av-value">Headline: ${esc(visual.typography?.headlineFont)}<br>Body: ${esc(visual.typography?.bodyFont)}</div>
    </div>
    <div class="av-section">
      <div class="av-label">Mood Direction</div>
      <div class="av-value">${esc(visual.moodDirection)}</div>
    </div>
  `;
}

function render(container, agent, ctx, visual) {
  container.innerHTML = `
    <div class="av-fields"></div>
    <div class="av-cascade">Regenerating your visual identity will also update your Playbook, Brand Book, and Logo package.</div>
    <div class="av-section">
      <div class="av-label">Feedback for this regeneration (optional)</div>
      <textarea class="av-feedback" placeholder="e.g. Try a warmer, more saturated palette…"></textarea>
    </div>
    <div class="av-footer">
      <button class="av-submit" type="button">Regenerate</button>
      <span class="av-status"></span>
    </div>
  `;
  renderFields(container.querySelector('.av-fields'), visual);
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
      const queued = await submitRegeneration(agent.engagementId, agent.agentId, agent.direction, feedback.value.trim());
      const job = await pollRegeneration(agent.engagementId, queued.id, (j) => {
        status.textContent = j.status === 'RUNNING' ? 'Regenerating…' : 'Queued…';
      });
      renderApprovalGate(container, {
        engagementId: agent.engagementId,
        job,
        renderDraft: (el, draft) => renderFields(el, draft.visualIdentity),
        onApprove: async () => {
          ctx.onAssetGenerated?.();
          const detail = await fetchEngagementDetail(agent.engagementId);
          const dir = findDirectionOutput(detail, agent.direction);
          render(container, agent, ctx, dir.visualIdentity);
        },
        onDiscard: async () => {
          const detail = await fetchEngagementDetail(agent.engagementId);
          const dir = findDirectionOutput(detail, agent.direction);
          render(container, agent, ctx, dir.visualIdentity);
        },
      });
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
