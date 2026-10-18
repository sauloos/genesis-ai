/**
 * Button widget — one freely placed navigation button with its own label, direction
 * (next/back) and alignment. Uses the host's `outcome-btn` class, so the host disables
 * it while another widget on the page is still blocking.
 */
export function mount(container, widget, ctx) {
  const config = widget.config || {};
  const align = ['left', 'center', 'right'].includes(config.align) ? config.align : 'right';
  const isBack = config.action === 'back';

  container.classList.add('wb-row', 'align-' + align);
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'outcome-btn' + (isBack ? ' outcome-btn-back' : '');
  btn.textContent = config.label || (isBack ? 'Back' : 'Continue');
  btn.onclick = () => ctx.advance?.(isBack ? 'back' : 'next');
  container.appendChild(btn);
}
