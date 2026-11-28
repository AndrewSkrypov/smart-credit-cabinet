import { Box, Button, IconButton, Stack, Typography, useMediaQuery } from '@mui/material';
import { RefObject, useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { visuallyHidden } from '../../theme/visuallyHidden';
import { KeshaBird } from './KeshaBird';
import { KeshaPlacement, KeshaPosition, measureKeshaPlacement } from './keshaPlacement';
import { useKesha } from './KeshaProvider';

interface Props {
  applicationId: string;
  target: RefObject<HTMLDivElement>;
  delayInput: RefObject<HTMLInputElement>;
  delayDays: number;
  delayRevision: number;
  available: boolean;
  patched: boolean;
  applyDelay: (expectedDays: number, delayRevision: number) => boolean;
  undoDelay: () => boolean;
}

type Offer = { applicationId: string; previousDays: number; delayRevision: number; from: DOMRect | undefined };
function samePosition(a: KeshaPosition | null | undefined, b: KeshaPosition | null | undefined) {
  return a?.left === b?.left && a?.top === b?.top && a?.width === b?.width && a?.maxHeight === b?.maxHeight;
}

export function KeshaDelayHint({ applicationId, target, delayInput, delayDays, delayRevision, available, patched, applyDelay, undoDelay }: Props) {
  const kesha = useKesha();
  const id = useId();
  const [visible, setVisible] = useState(false);
  const [anchorInView, setAnchorInView] = useState(false);
  const [placement, setPlacement] = useState<KeshaPlacement | null>(null);
  const [offer, setOffer] = useState<Offer | null>(null);
  const [expanded, setExpanded] = useState(false);
  const [flying, setFlying] = useState(false);
  const bird = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLElement>(null);
  const cloud = useRef<HTMLDivElement>(null);
  const applyButton = useRef<HTMLButtonElement>(null);
  const undoButton = useRef<HTMLButtonElement>(null);
  const focusUndo = useRef(false);
  const focusExpanded = useRef(false);
  const hasFlown = useRef(false);
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)');
  const setAttached = kesha?.setAttached;
  const enabled = !!kesha?.enabled;
  const overlay = kesha?.overlay;

  useEffect(() => {
    if (!enabled || !target.current || typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(([entry]) => {
      setVisible(entry.isIntersecting && entry.intersectionRatio === 1);
      setAnchorInView(entry.isIntersecting);
    }, { threshold: [0, 1], rootMargin: '-72px 0px 0px 0px' });
    observer.observe(target.current);
    return () => observer.disconnect();
  }, [enabled, target]);

  useEffect(() => {
    if (!kesha?.enabled || !kesha.proactive || !kesha.overlay || kesha.blocked || kesha.editing || !available || !visible || offer ||
      !Number.isInteger(delayDays) || delayDays < 0 || delayDays > 365 || delayDays === 2 ||
      !kesha.isCurrentAccount() || kesha.offered.has(applicationId)) return;
    kesha.offered.add(applicationId);
    setOffer({ applicationId, previousDays: delayDays, delayRevision, from: kesha.dockBird.current?.getBoundingClientRect() });
  }, [applicationId, available, delayDays, delayRevision, kesha, offer, visible]);

  const validOffer = offer?.applicationId === applicationId && offer?.delayRevision === delayRevision && (patched || delayDays === offer.previousDays);
  const sceneOpen = enabled && !!kesha?.proactive && !!overlay && available && validOffer;
  const attached = sceneOpen && anchorInView && !!placement;
  const blocked = !!kesha?.blocked;

  useLayoutEffect(() => {
    const anchor = target.current;
    const speech = cloud.current;
    if (!sceneOpen || !anchorInView || !anchor || !speech) {
      setPlacement(null);
      return;
    }
    let frame = 0;
    const measure = () => {
      const next = measureKeshaPlacement(anchor.getBoundingClientRect(), speech);
      setPlacement((previous) => previous?.collapsed === next?.collapsed &&
        samePosition(previous?.bird, next?.bird) && samePosition(previous?.full, next?.full) &&
        samePosition(previous?.compact, next?.compact) ? previous : next);
    };
    const schedule = () => {
      if (frame) return;
      frame = window.requestAnimationFrame(() => {
        frame = 0;
        measure();
      });
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(schedule);
    observer?.observe(anchor);
    observer?.observe(speech);
    window.addEventListener('scroll', schedule, { capture: true, passive: true });
    window.addEventListener('resize', schedule);
    const viewport = window.visualViewport;
    viewport?.addEventListener('scroll', schedule);
    viewport?.addEventListener('resize', schedule);
    return () => {
      if (frame) window.cancelAnimationFrame(frame);
      observer?.disconnect();
      window.removeEventListener('scroll', schedule, true);
      window.removeEventListener('resize', schedule);
      viewport?.removeEventListener('scroll', schedule);
      viewport?.removeEventListener('resize', schedule);
    };
  }, [anchorInView, overlay, patched, sceneOpen, target]);

  useLayoutEffect(() => {
    if (!attached || !setAttached) return;
    setAttached(id);
    return () => setAttached((current) => current === id ? null : current);
  }, [attached, id, setAttached]);

  useLayoutEffect(() => {
    const artwork = bird.current;
    const from = offer?.from;
    if (!attached || hasFlown.current || !artwork?.animate || !from?.width || !from.height || reducedMotion || blocked) {
      setFlying(false);
      return;
    }
    hasFlown.current = true;
    const to = artwork.getBoundingClientRect();
    const x = from.left + (from.width - to.width) / 2 - to.left;
    const y = from.top + (from.height - to.height) / 2 - to.top;
    const scale = from.width / to.width;
    setFlying(true);
    const flight = artwork.animate([
      { transform: `translate(${x}px, ${y}px) scale(${scale})` },
      { transform: `translate(${x * 0.35}px, ${y * 0.35 - 18}px) scale(0.9)`, offset: 0.62 },
      { transform: 'none' }
    ], { duration: 420, easing: 'cubic-bezier(0.22, 0.7, 0.35, 1)' });
    void flight.finished.then(() => setFlying(false), () => undefined);
    return () => flight.cancel();
  }, [attached, blocked, offer, reducedMotion]);

  useLayoutEffect(() => {
    if (patched && focusUndo.current) {
      focusUndo.current = false;
      undoButton.current?.focus({ preventScroll: true });
    }
  }, [patched]);

  useLayoutEffect(() => {
    if (expanded && focusExpanded.current) {
      focusExpanded.current = false;
      (patched ? undoButton : applyButton).current?.focus({ preventScroll: true });
    }
  }, [expanded, patched]);

  useEffect(() => {
    if (!attached || patched || blocked) return;
    const dismiss = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented) return;
      event.preventDefault();
      if (panel.current?.contains(document.activeElement)) delayInput.current?.focus({ preventScroll: true });
      setOffer(null);
    };
    document.addEventListener('keydown', dismiss);
    return () => document.removeEventListener('keydown', dismiss);
  }, [attached, blocked, delayInput, patched]);

  function close() {
    if (panel.current?.contains(document.activeElement)) delayInput.current?.focus({ preventScroll: true });
    setOffer(null);
  }

  function expand(trigger: HTMLButtonElement) {
    focusExpanded.current = trigger === document.activeElement;
    setExpanded(true);
  }

  if (!overlay) return null;
  const message = patched ? 'Подставил 2 дня. Расчёт ещё не обновлён.' : 'А если деньги от покупателя поступят на два дня позже?';
  const announcement = patched ? 'Задержка поступления изменена на 2 дня. Расчёт пока прежний.' : 'Кеша предлагает примерить задержку поступления на 2 дня.';
  const panelReady = attached && !flying && !blocked;
  const compact = !!placement?.collapsed && !expanded;
  const speechVisible = panelReady && !compact;
  const birdPosition = placement?.bird;
  const cloudPosition = placement?.full;
  const compactPosition = placement?.compact;
  const birdOpensHint = compact && !compactPosition;
  const birdLabel = birdOpensHint ? (patched ? 'Правка Кеши' : 'Есть подсказка') : 'Открыть помощника';
  const leftOfBird = cloudPosition && birdPosition && cloudPosition.left < birdPosition.left;
  const tailTop = birdPosition && cloudPosition ? birdPosition.top + 17 - cloudPosition.top : -1;

  return createPortal(
    <>
      <Box role="status" sx={visuallyHidden}>{panelReady ? (compact ? 'Есть подсказка Кеши о задержке поступления.' : announcement) : ''}</Box>
      {sceneOpen && offer && (
        <Box component="aside" aria-label="Подсказка Кеши" aria-hidden={!panelReady || undefined} ref={panel}
          data-kesha-mode={compact ? 'compact' : 'cloud'}
          style={{ left: birdPosition?.left, top: birdPosition?.top, visibility: attached ? 'visible' : 'hidden' }}
          sx={{ position: 'absolute', opacity: blocked ? 0 : 1 }}>
          <IconButton ref={bird} aria-label={birdLabel} title={birdOpensHint ? birdLabel : undefined} tabIndex={panelReady ? 0 : -1}
            onClick={(event) => {
              if (!panelReady) return;
              if (birdOpensHint) expand(event.currentTarget);
              else kesha?.setChatOpen(true);
            }} sx={{
              p: 0, width: 44, height: 44,
              pointerEvents: panelReady ? 'auto' : 'none', zIndex: 1,
              '& svg': { width: '100%', height: '100%', display: 'block' }
            }}>
            <KeshaBird pose={flying ? 'fly' : compact ? 'idle' : 'point'} />
          </IconButton>
          {compact && compactPosition && birdPosition && <Button variant="contained" color="secondary" size="small" tabIndex={panelReady ? 0 : -1}
            aria-label={patched ? 'Правка Кеши' : 'Есть подсказка'}
            style={{ left: compactPosition.left - birdPosition.left, top: compactPosition.top - birdPosition.top, width: compactPosition.width }}
            sx={{ position: 'absolute', px: 0.75, minHeight: 44, pointerEvents: panelReady ? 'auto' : 'none', visibility: flying ? 'hidden' : 'inherit' }}
            onClick={(event) => { if (panelReady) expand(event.currentTarget); }}>{patched ? 'Правка Кеши' : 'Что если?'}</Button>}
          <Box data-testid="kesha-cloud" style={cloudPosition && birdPosition ? {
            left: cloudPosition.left - birdPosition.left, top: cloudPosition.top - birdPosition.top
          } : undefined} sx={{
            position: 'absolute', visibility: flying || compact ? 'hidden' : 'inherit',
            borderRadius: '14px', bgcolor: 'background.paper', boxShadow: '0 8px 24px rgba(3, 3, 6, 0.16)',
            '&::before': {
              content: tailTop >= 4 && tailTop <= 36 ? '""' : 'none', position: 'absolute',
              [leftOfBird ? 'right' : 'left']: -4, top: tailTop, width: 9, height: 9,
              bgcolor: 'background.paper', transform: 'rotate(45deg)'
            }
          }}>
            <Box ref={cloud} aria-hidden={!speechVisible || undefined} sx={{
              boxSizing: 'border-box', maxHeight: placement?.full.maxHeight, overflowY: 'auto', overscrollBehavior: 'contain',
              p: 1,
              '& button': { pointerEvents: speechVisible ? 'auto' : 'none', px: 0.75, fontSize: '0.8125rem' }
            }}>
              <Typography variant="body2" fontWeight={650}>{message}</Typography>
              {!patched && offer.previousDays !== 0 && (
                <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                  Заменю задержку с {offer.previousDays} на 2 дня.
                </Typography>
              )}
              {patched ? (
                <Button ref={undoButton} variant="outlined" size="small" tabIndex={speechVisible ? 0 : -1} sx={{ mt: 1 }} onClick={() => {
                  if (speechVisible && kesha?.isCurrentAccount() && undoDelay()) close();
                }}>Отменить</Button>
              ) : (
                <Stack direction="row" flexWrap="wrap" useFlexGap gap={0.5} sx={{ mt: 1 }}>
                  <Button ref={applyButton} variant="contained" color="secondary" size="small" tabIndex={speechVisible ? 0 : -1} onClick={(event) => {
                    if (!speechVisible || !kesha?.isCurrentAccount()) return;
                    focusUndo.current = event.currentTarget === document.activeElement;
                    if (!applyDelay(offer.previousDays, offer.delayRevision)) close();
                  }}>Подставить 2 дня</Button>
                  <Button size="small" tabIndex={speechVisible ? 0 : -1} onClick={close}>Не сейчас</Button>
                </Stack>
              )}
            </Box>
          </Box>
        </Box>
      )}
    </>,
    overlay
  );
}
