/**
 * Consultant's Playground view — the admin "pure consultant" (no customer context). The full
 * admin console (brand sandbox selection, conversations, streaming) lives at /console.html,
 * which this view embeds rather than reimplementing.
 */
export function mount(container) {
  container.innerHTML = `<iframe class="cc-pg-frame" src="/console.html?embed=1&origin=playground"
    title="Consultant console"></iframe>`;
}
