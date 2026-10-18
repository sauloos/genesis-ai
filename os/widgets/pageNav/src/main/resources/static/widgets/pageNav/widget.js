/**
 * Page navigation widget — Back (if the page has a "back" outcome) on the left, every
 * other outcome on the right. Renders nothing on a flow's end page, which is terminal.
 */
function outcomeButton(outcome, ctx, back) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'outcome-btn' + (back ? ' outcome-btn-back' : '');
  btn.textContent = outcome.label;
  btn.onclick = () => ctx.advance?.(outcome.key);
  return btn;
}

export function mount(container, widget, ctx) {
  if (ctx.page?.ended) return;
  const outcomes = ctx.outcomes || [];
  const backOutcome = outcomes.find(o => o.key === 'back');

  const left = document.createElement('div');
  left.className = 'wpn-left';
  if (backOutcome) left.appendChild(outcomeButton(backOutcome, ctx, true));

  const right = document.createElement('div');
  right.className = 'wpn-right';
  outcomes.filter(o => o.key !== 'back').forEach(o => right.appendChild(outcomeButton(o, ctx, false)));

  container.append(left, right);
}
