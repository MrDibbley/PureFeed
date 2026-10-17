// Read-only (plus scroll back to top). Layout model of the feed list.
(() => {
  const vs = document.querySelector('[data-type="vscroller"]');
  const cs = e => { const s = getComputedStyle(e); return { display: s.display, position: s.position, transform: s.transform, overflowY: s.overflowY, height: s.height }; };
  const kids = [...vs.children];
  const units = kids.filter(u => u.hasAttribute('data-tracking-duration-id'));
  const sep = kids.find(u => !u.hasAttribute('data-tracking-duration-id') && Math.round(u.getBoundingClientRect().height) === 1);
  // header text order in an ad unit, as positions only
  const ad = units.find(u => [...u.querySelectorAll('[data-mcomponent$="TextArea"]')].some(t => /^Ad/.test(t.textContent)));
  const adIdx = ad && [...ad.querySelectorAll('[data-mcomponent$="TextArea"]')].findIndex(t => /^Ad/.test(t.textContent));
  const adTop = ad && Math.round([...ad.querySelectorAll('[data-mcomponent$="TextArea"]')][adIdx].getBoundingClientRect().top - ad.getBoundingClientRect().top);
  const fol = units.find(u => [...u.querySelectorAll('[data-mcomponent$="TextArea"]')].some(t => t.textContent.trim() === 'Follow'));
  const folIdx = fol && [...fol.querySelectorAll('[data-mcomponent$="TextArea"]')].findIndex(t => t.textContent.trim() === 'Follow');
  const r = { vscroller: cs(vs), vsParent: cs(vs.parentElement), docScroller: document.scrollingElement.tagName,
    unit: cs(units[3]), unitStyleAttr: units[3].getAttribute('style'), sep: sep && { ...cs(sep), style: sep.getAttribute('style') },
    adTextAreaIndex: adIdx, adOffsetFromUnitTop: adTop, followTextAreaIndex: folIdx,
    unitAttrs: [...units[3].attributes].map(a => a.name + '=' + (a.name === 'data-tracking-duration-id' ? '<id>' : a.value.slice(0, 30))) };
  window.scrollTo(0, 0);
  return r;
})()
