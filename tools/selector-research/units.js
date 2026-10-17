// Read-only. Describe each child of the feed vscroller. Text is only reported when it
// repeats in >=2 units (UI chrome) or matches a known label pattern; else just its length.
(() => {
  // Exact UI strings only: a loose regex here lets names and group titles through.
  const LABEL = /^(ad|sponsored|suggested for you|reels|people you may know|join|follow|add friend|see all|memories|on this day|create story|open app|shop now|learn more|•)$/i;
  const vs = document.querySelector('[data-type="vscroller"]');
  const units = [...vs.children];
  const textsOf = el => [...el.querySelectorAll('[data-mcomponent="TextArea"],[data-mcomponent="ServerTextArea"]')]
      .map(t => t.textContent.replace(/[\uE000-\uF8FF]|[\u{F0000}-\u{FFFFD}]/gu, '').trim()).filter(t => t && t.length <= 40);
  const seen = {};
  units.forEach(u => new Set(textsOf(u)).forEach(t => seen[t] = (seen[t]||0)+1));
  const show = t => (seen[t] >= 3 || LABEL.test(t)) ? t : `<${t.length}>`;
  const ariaShow = a => (LABEL.test(a) && a.length < 40) ? a : `<aria ${a.length}>`;
  return { unitCount: units.length, units: units.map((u, i) => ({
    i, h: Math.round(u.getBoundingClientRect().height),
    attrs: [...u.attributes].map(a => a.name + (a.name.match(/type|mcomponent|tti/) ? '=' + a.value : '')).join(' '),
    tracking: u.querySelectorAll('[data-tracking-duration-id]').length,
    imgs: u.querySelectorAll('[data-type="image"]').length,
    buttons: u.querySelectorAll('[role="button"]').length,
    texts: textsOf(u).slice(0, 14).map(show),
    arias: [...u.querySelectorAll('[aria-label]')].slice(0, 10).map(e => ariaShow(e.getAttribute('aria-label'))),
  }))};
})()
