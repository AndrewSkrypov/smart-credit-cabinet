type Bounds = Pick<DOMRectReadOnly, 'left' | 'top' | 'right' | 'bottom'>;
type Obstruction = { bounds: Bounds; weight: number };
export type KeshaPosition = { left: number; top: number; width: number; maxHeight: number };
export type KeshaPlacement = { bird: KeshaPosition; full: KeshaPosition | null; compact: KeshaPosition | null; collapsed: boolean };

const EDGE = 12;
const GAP = 10;
const WIDTH = 236;
const CHIP_WIDTH = 110;
const BIRD = 44;
const CONTROLS = 'input:not([type="hidden"]), textarea, select, button, a[href], label';
const TEXT = 'td, th, [class*="MuiTypography-"]';

function overlap(a: Bounds, b: Bounds) {
  return Math.max(0, Math.min(a.right, b.right) - Math.max(a.left, b.left)) *
    Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top));
}

function clamp(value: number, min: number, max: number) {
  return Math.max(min, Math.min(value, max));
}

function boundsOf({ left, top, width, maxHeight }: KeshaPosition) {
  return { left, top, right: left + width, bottom: top + maxHeight };
}

function distance(a: Bounds, b: Bounds) {
  return Math.hypot(Math.max(0, a.left - b.right, b.left - a.right), Math.max(0, a.top - b.bottom, b.top - a.bottom));
}

function weightedOverlap(rect: Bounds, controls: Obstruction[]) {
  return controls.reduce((area, { bounds, weight }) => area + overlap(rect, bounds) * weight, 0);
}

function placeBird(anchor: Bounds, viewport: Bounds, controls: Obstruction[]) {
  const { left, top, right, bottom } = viewport;
  const x = clamp(anchor.left, left, right - BIRD);
  const y = clamp((anchor.top + anchor.bottom - BIRD) / 2, top, bottom - BIRD);
  const candidates = [
    [anchor.left - BIRD - 6, y], [anchor.right + 6, y],
    [anchor.left - BIRD, y], [anchor.right, y],
    [x, anchor.bottom + 6], [x, anchor.top - BIRD - 6]
  ];
  let best: { score: number; position: KeshaPosition } | null = null;
  for (const [x, y] of candidates) {
    const position = { left: x, top: y, width: BIRD, maxHeight: BIRD };
    const rect = boundsOf(position);
    if (x < left || x + BIRD > right || y < top || y + BIRD > bottom || overlap(rect, anchor)) continue;
    const score = weightedOverlap(rect, controls) * 5 + distance(rect, anchor);
    if (!best || score < best.score) best = { score, position };
  }
  return best?.position;
}

function findPosition(anchor: Bounds, bird: Bounds, viewport: Bounds, controls: Obstruction[], width: number, height: number) {
  const { left, top, right, bottom } = viewport;
  const xs = new Set([bird.right + GAP, bird.left - width - GAP, anchor.right + GAP, anchor.left - width - GAP, anchor.left, left, right - width]
    .map((x) => clamp(x, left, right - width)));
  let best: { score: number; covered: number; position: KeshaPosition } | null = null;
  for (const x of xs) {
    const rightEdge = x + width;
    const across = controls.filter(({ bounds }) => bounds.left < rightEdge && bounds.right > x);
    const ys = new Set([bird.top, bird.bottom + GAP, bird.top - height - GAP, anchor.bottom + GAP, anchor.top - height - GAP, top, bottom - height]);
    for (const { bounds } of across) {
      ys.add(bounds.bottom + GAP);
      ys.add(bounds.top - height - GAP);
    }
    for (const y of ys) {
      if (y < top || y + height > bottom) continue;
      const rect = { left: x, right: rightEdge, top: y, bottom: y + height };
      if (overlap(rect, anchor) || overlap(rect, bird)) continue;
      const covered = weightedOverlap(rect, across);
      const score = (covered ? 20_000 + covered * 5 : 0) + distance(rect, bird) + distance(rect, anchor) * 0.1;
      if (!best || score < best.score) best = { score, covered, position: { left: x, top: y, width, maxHeight: height } };
    }
  }
  return best;
}

function visibleObstructions(viewport: Bounds) {
  const controls = Array.from(document.querySelectorAll(`header, main :is(${CONTROLS})`), (element) => ({
    bounds: element.getBoundingClientRect(), weight: 30
  })).filter(({ bounds }) => overlap(bounds, viewport));
  const range = document.createRange();
  for (const element of document.querySelectorAll(`main :is(${TEXT})`)) {
    const bounds = element.getBoundingClientRect();
    if (!overlap(bounds, viewport)) continue;
    range.selectNodeContents(element);
    const lines = typeof range.getClientRects === 'function' ? Array.from(range.getClientRects()) : [bounds];
    for (const line of lines) if (overlap(line, viewport)) controls.push({ bounds: line, weight: 1 });
    if (typeof range.getClientRects !== 'function') continue;
    const textNodes = document.createTreeWalker(element, NodeFilter.SHOW_TEXT);
    for (let node = textNodes.nextNode(); node; node = textNodes.nextNode()) {
      for (const value of (node.textContent ?? '').matchAll(/\d+(?:[\s.,]\d+)*\s*[%₽]?/g)) {
        range.setStart(node, value.index);
        range.setEnd(node, value.index + value[0].length);
        for (const line of range.getClientRects()) {
          if (overlap(line, viewport)) controls.push({ bounds: line, weight: 40 });
        }
      }
    }
  }
  return controls;
}

export function measureKeshaPlacement(anchor: DOMRect, cloud: HTMLElement): KeshaPlacement | null {
  const visualViewport = window.visualViewport;
  const visibleHeight = visualViewport?.height ?? window.innerHeight;
  const edge = visibleHeight <= 540 ? 8 : EDGE;
  const left = (visualViewport?.offsetLeft ?? 0) + edge;
  const top = (visualViewport?.offsetTop ?? 0) + edge;
  const right = left + (visualViewport?.width ?? (document.documentElement.clientWidth || window.innerWidth)) - edge * 2;
  const bottom = top + visibleHeight - edge * 2;
  const viewport = { left, top, right, bottom };
  const width = Math.min(WIDTH, right - left);
  if (width <= 0 || bottom <= top || !overlap(anchor, viewport)) return null;

  const cloudWidth = `${width}px`;
  if (cloud.style.width !== cloudWidth) cloud.style.width = cloudWidth;
  const height = Math.min(bottom - top, Math.max(52, cloud.scrollHeight));
  const controls = visibleObstructions(viewport);
  const bird = placeBird(anchor, { left: left - edge, top: top - edge, right: right + edge, bottom: bottom + edge }, controls);
  if (!bird) return null;
  const birdBounds = boundsOf(bird);
  const full = findPosition(anchor, birdBounds, viewport, controls, width, height);
  if (full && !full.covered && distance(boundsOf(full.position), birdBounds) <= 60) {
    return { bird, full: full.position, compact: null, collapsed: false };
  }

  // A crowded screen gets an invitation rather than an unsolicited card over the form.
  const compactWidth = Math.min(CHIP_WIDTH, width);
  const chip = findPosition(anchor, birdBounds, viewport, controls, compactWidth, 44);
  const compact = chip && !chip.covered && distance(boundsOf(chip.position), birdBounds) <= 60 ? chip.position : null;
  return { bird, full: full && !full.covered ? full.position : null, compact, collapsed: true };
}
