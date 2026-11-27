import {
  Box,
  Button,
  Chip,
  CircularProgress,
  IconButton,
  ListItemIcon,
  ListItemText,
  Menu,
  MenuItem,
  Modal,
  Paper,
  Stack,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme
} from '@mui/material';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import HelpOutlineRoundedIcon from '@mui/icons-material/HelpOutlineRounded';
import PlayArrowRoundedIcon from '@mui/icons-material/PlayArrowRounded';
import RestartAltRoundedIcon from '@mui/icons-material/RestartAltRounded';
import SchoolOutlinedIcon from '@mui/icons-material/SchoolOutlined';
import { createContext, ReactNode, RefObject, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { roleHome, useAuth } from '../../auth/AuthProvider';
import { tokens } from '../../theme/theme';
import { Role } from '../../types';
import {
  findPageTour,
  OnboardingState,
  pageTourIds,
  readOnboardingState,
  RoleTourConfig,
  roleTours,
  TourStep,
  writeOnboardingState
} from './tourSteps';

interface SpotlightRect {
  top: number;
  left: number;
  width: number;
  height: number;
}

interface TourRequest {
  userId: string;
  role: Role;
  routeKey: string;
  config: RoleTourConfig;
  tourId?: string;
  trigger?: HTMLElement;
  destination?: string;
}

interface TourTarget {
  step: TourStep;
  element: HTMLElement;
}

interface OnboardingContextValue {
  bannerVisible: boolean;
  welcomeTitle: string;
  welcomeSubtitle: string;
  hasPageTour: boolean;
  /** Starts the tour of the current screen: the page tour where there is one, otherwise the cabinet tour. */
  startTour: (trigger?: HTMLElement) => void;
  startCabinetTour: (trigger?: HTMLElement) => void;
  dismissBanner: () => void;
  resetTour: () => void;
}

/** Height of the sticky cabinet header on phones, so a highlighted block is not hidden under it. */
const MOBILE_HEADER_OFFSET = 88;

const OnboardingContext = createContext<OnboardingContextValue | null>(null);

function findVisibleTarget(root: HTMLElement, selector: string): HTMLElement | null {
  const candidates = root.querySelectorAll<HTMLElement>(selector);
  for (const el of candidates) {
    const style = window.getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') continue;
    const rect = el.getBoundingClientRect();
    if (rect.width > 0 && rect.height > 0) return el;
  }
  return null;
}

function readTargets(root: HTMLElement, steps: TourStep[]) {
  return steps.flatMap((step) => {
    const element = findVisibleTarget(root, step.selector);
    return element ? [{ step, element }] : [];
  });
}

export function OnboardingProvider({ children, targetRootRef }: { children: ReactNode; targetRootRef: RefObject<HTMLElement> }) {
  const { session } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  const userId = session?.user.id ?? '';
  const role = session?.user.role ?? 'CLIENT';
  const homeConfig = roleTours[role];
  const homePath = roleHome[role];
  const pathname = location.pathname.replace(/\/+$/, '') || '/';
  const onHome = pathname === homePath;
  const pageTour = onHome ? null : findPageTour(role, pathname);
  // The tour of the screen the user is on: the cabinet tour at home, a page tour on key screens.
  const screenTourId = onHome ? undefined : pageTour?.id;
  const screenConfig = onHome ? homeConfig : pageTour;

  const [status, setStatus] = useState<OnboardingState>(() =>
    userId && screenConfig ? readOnboardingState(userId, role, screenTourId) : 'dismissed'
  );
  const [request, setRequest] = useState<TourRequest | null>(null);
  const focusFrame = useRef<number | null>(null);
  const sameAccount = request?.userId === userId && request.role === role;
  const runningTour = sameAccount && request.routeKey === location.key && !request.destination ? request : null;

  useEffect(() => {
    setStatus(userId && screenConfig ? readOnboardingState(userId, role, screenTourId) : 'dismissed');
  }, [role, screenConfig, screenTourId, userId]);

  useEffect(() => {
    if (!request) return;
    if (!sameAccount) {
      setRequest(null);
    } else if (request.routeKey !== location.key) {
      setRequest(request.destination === pathname ? { ...request, routeKey: location.key, destination: undefined } : null);
    }
  }, [location.key, pathname, request, sameAccount]);

  const cancelFocusRestore = useCallback(() => {
    if (focusFrame.current === null) return;
    window.cancelAnimationFrame(focusFrame.current);
    focusFrame.current = null;
  }, []);

  useEffect(() => cancelFocusRestore, [cancelFocusRestore, location.key, role, userId]);

  const startCabinetTour = useCallback((trigger?: HTMLElement) => {
    cancelFocusRestore();
    setRequest({
      userId, role, routeKey: location.key, config: homeConfig, trigger,
      destination: onHome ? undefined : homePath
    });
    if (!onHome) navigate(homePath);
  }, [cancelFocusRestore, homeConfig, homePath, location.key, navigate, onHome, role, userId]);

  const startTour = useCallback((trigger?: HTMLElement) => {
    if (!pageTour) {
      startCabinetTour(trigger);
      return;
    }
    cancelFocusRestore();
    setRequest({ userId, role, routeKey: location.key, config: pageTour, tourId: pageTour.id, trigger });
  }, [cancelFocusRestore, location.key, pageTour, role, startCabinetTour, userId]);

  const finishTour = useCallback((nextState: 'completed' | 'dismissed') => {
    if (!runningTour) return;
    setRequest(null);
    if (userId) {
      writeOnboardingState(userId, role, nextState, runningTour.tourId);
      if (runningTour.tourId === screenTourId) setStatus(nextState);
    }
    cancelFocusRestore();
    const trigger = runningTour.trigger;
    focusFrame.current = window.requestAnimationFrame(() => {
      focusFrame.current = null;
      const target = trigger?.isConnected ? trigger : targetRootRef.current?.querySelector<HTMLElement>('[data-tour="training-trigger"]');
      target?.focus();
    });
  }, [cancelFocusRestore, role, runningTour, screenTourId, targetRootRef, userId]);

  const dismissBanner = useCallback(() => {
    if (userId) {
      writeOnboardingState(userId, role, 'dismissed', screenTourId);
      setStatus('dismissed');
    }
  }, [role, screenTourId, userId]);

  const resetTour = useCallback(() => {
    if (userId) {
      writeOnboardingState(userId, role, null);
      pageTourIds(role).forEach((tourId) => writeOnboardingState(userId, role, null, tourId));
      setStatus(screenConfig ? null : 'dismissed');
    }
  }, [role, screenConfig, userId]);

  const bannerVisible = Boolean(userId) && Boolean(screenConfig) && status === null && !runningTour;

  const value = useMemo<OnboardingContextValue>(() => ({
    bannerVisible,
    welcomeTitle: screenConfig?.welcomeTitle ?? homeConfig.welcomeTitle,
    welcomeSubtitle: screenConfig?.welcomeSubtitle ?? homeConfig.welcomeSubtitle,
    hasPageTour: Boolean(pageTour),
    startTour,
    startCabinetTour,
    dismissBanner,
    resetTour
  }), [bannerVisible, dismissBanner, homeConfig, pageTour, resetTour, screenConfig, startCabinetTour, startTour]);

  return (
    <OnboardingContext.Provider value={value}>
      {children}
      {runningTour && <OnboardingDialog config={runningTour.config} targetRootRef={targetRootRef} finishTour={finishTour} />}
    </OnboardingContext.Provider>
  );
}

function OnboardingDialog({ config, targetRootRef, finishTour }: {
  config: RoleTourConfig;
  targetRootRef: RefObject<HTMLElement>;
  finishTour: (state: 'completed' | 'dismissed') => void;
}) {
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('sm'));
  const reduceMotion = useMediaQuery('(prefers-reduced-motion: reduce)');
  const [targets, setTargets] = useState<TourTarget[]>([]);
  const [selectedStep, setSelectedStep] = useState<TourStep | null>(null);
  const [measurement, setMeasurement] = useState<{ element: HTMLElement; rect: SpotlightRect } | null>(null);

  const stepIndex = Math.max(0, targets.findIndex(({ step }) => step === selectedStep));
  const currentStep = targets[stepIndex]?.step;
  const target = targets[stepIndex]?.element;
  const targetRect = measurement && measurement.element === target ? measurement.rect : null;

  useEffect(() => {
    const root = targetRootRef.current;
    if (!root) return;

    const measure = () => {
      if (!target || !target.isConnected) {
        setMeasurement(null);
        return;
      }
      const rect = target.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) {
        setMeasurement(null);
        return;
      }
      const pad = 6;
      // A full-width block must not push the frame past the screen edge (horizontal scroll on phones).
      const left = Math.max(4, rect.left - pad);
      const right = Math.min((document.documentElement.clientWidth || window.innerWidth) - 4, rect.right + pad);
      const top = Math.max(4, rect.top - pad);
      const next = {
        top,
        left,
        width: Math.max(0, right - left),
        height: Math.max(0, rect.bottom + pad - top)
      };
      setMeasurement(previous => previous?.element === target && previous.rect.top === next.top &&
        previous.rect.left === next.left && previous.rect.width === next.width && previous.rect.height === next.height
        ? previous : { element: target, rect: next });
    };

    const refresh = () => {
      const visible = readTargets(root, config.steps);
      // Shared header and edit controls may render before the page's data-backed content.
      const next = visible.some(({ step }) => step.id === config.readyStepId) ? visible : [];
      setTargets(previous => previous.length === next.length && previous.every(({ step, element }, index) =>
        step === next[index].step && element === next[index].element
      ) ? previous : next);
      if (next.some(({ element }) => element === target)) measure();
      else setMeasurement(null);
    };

    if (target) {
      const behavior = reduceMotion ? 'instant' : 'smooth';
      if (isMobile) {
        // On a phone the step card covers the lower part of the screen: put the block right under the header.
        const top = target.getBoundingClientRect().top + window.scrollY - MOBILE_HEADER_OFFSET;
        window.scrollTo({ top: Math.max(0, top), behavior });
      } else {
        target.scrollIntoView?.({ behavior, block: 'center', inline: 'nearest' });
      }
    }

    // Data-bound steps can appear together after loading or in stages as sections render.
    const mutations = new MutationObserver(refresh);
    mutations.observe(root, {
      subtree: true,
      childList: true,
      characterData: true,
      attributes: true,
      attributeFilter: ['class', 'style', 'hidden', 'data-tour']
    });
    const dimensions = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(refresh);
    dimensions?.observe(root);
    if (target) dimensions?.observe(target);
    window.addEventListener('resize', refresh);
    window.addEventListener('scroll', measure, true);
    refresh();
    return () => {
      mutations.disconnect();
      dimensions?.disconnect();
      window.removeEventListener('resize', refresh);
      window.removeEventListener('scroll', measure, true);
    };
  }, [config, isMobile, reduceMotion, target, targetRootRef]);

  useEffect(() => {
    if (!currentStep) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'ArrowRight') {
        event.preventDefault();
        if (stepIndex < targets.length - 1) setSelectedStep(targets[stepIndex + 1].step);
        else finishTour('completed');
      } else if (event.key === 'ArrowLeft') {
        event.preventDefault();
        if (stepIndex > 0) setSelectedStep(targets[stepIndex - 1].step);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [currentStep, finishTour, stepIndex, targets]);

  const popoverPosition = useMemo(() => {
    if (isMobile || !targetRect || typeof window === 'undefined') return null;
    const cardWidth = 360;
    const estimatedHeight = 230;
    const gap = 14;
    const spaceBelow = window.innerHeight - (targetRect.top + targetRect.height);
    const top = spaceBelow >= estimatedHeight + gap
      ? targetRect.top + targetRect.height + gap
      : Math.max(12, targetRect.top - estimatedHeight - gap);
    const maxLeft = Math.max(12, window.innerWidth - cardWidth - 12);
    const left = Math.min(Math.max(12, targetRect.left), maxLeft);
    return { top, left, width: cardWidth };
  }, [isMobile, targetRect]);

  return (
    <Modal open hideBackdrop disableScrollLock disableRestoreFocus onClose={() => finishTour('dismissed')}>
      <Box role="dialog" aria-modal="true" tabIndex={-1} sx={{ position: 'fixed', inset: 0, outline: 'none' }}
        aria-label={currentStep ? `Шаг ${stepIndex + 1} из ${targets.length}: ${currentStep.title}` : 'Обучение'}
        aria-busy={!currentStep}>
        <Box
          data-testid="onboarding-backdrop"
          onClick={() => finishTour('dismissed')}
          sx={{
            position: 'fixed',
            inset: 0,
            zIndex: 1390,
            bgcolor: targetRect ? 'transparent' : 'rgba(15, 17, 21, 0.62)'
          }}
        />
        {targetRect && (
          <Box
            data-testid="onboarding-spotlight"
            sx={{
              position: 'fixed',
              top: targetRect.top,
              left: targetRect.left,
              width: targetRect.width,
              height: targetRect.height,
              borderRadius: 2.5,
              border: `2px solid ${tokens.red}`,
              boxShadow: '0 0 0 9999px rgba(15, 17, 21, 0.62), 0 0 0 4px rgba(239, 49, 36, 0.22)',
              pointerEvents: 'none',
              zIndex: 1395,
              transition: reduceMotion ? 'none' : 'all 180ms ease'
            }}
          />
        )}
        <Paper
          elevation={10}
          sx={{
            position: 'fixed',
            zIndex: 1400,
            p: 2.5,
            borderRadius: 3,
            border: '1px solid',
            borderColor: 'divider',
            ...(popoverPosition
              ? { top: popoverPosition.top, left: popoverPosition.left, width: popoverPosition.width }
              : { left: 12, right: 12, bottom: 12, maxWidth: 480, mx: 'auto' })
          }}
        >
          <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1} sx={{ mb: 1 }}>
            {currentStep && (
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Chip size="small" color="error" variant="outlined" label={currentStep.badge} />
                <Typography variant="caption" color="text.secondary" fontWeight={600}>
                  Шаг {stepIndex + 1} из {targets.length}
                </Typography>
              </Stack>
            )}
            <IconButton autoFocus sx={{ ml: 'auto' }} size="small" aria-label="Закрыть обучение" onClick={() => finishTour('dismissed')}>
              <CloseRoundedIcon fontSize="small" />
            </IconButton>
          </Stack>

          {currentStep ? (
            <>
              <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 0.75, overflowWrap: 'anywhere' }}>
                {currentStep.title}
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mb: 2, lineHeight: 1.55, overflowWrap: 'anywhere' }}>
                {currentStep.description}
              </Typography>

              <Stack direction="row" spacing={0.75} sx={{ mb: 2 }} role="group" aria-label="Шаги обучения">
                {targets.map(({ step }, index) => (
                  <Box
                    key={step.id}
                    component="button"
                    type="button"
                    aria-label={`Перейти к шагу ${index + 1}`}
                    aria-current={index === stepIndex ? 'step' : undefined}
                    onClick={() => setSelectedStep(step)}
                    sx={{
                      p: 0,
                      border: 0,
                      cursor: 'pointer',
                      height: 6,
                      width: index === stepIndex ? 24 : 8,
                      borderRadius: 99,
                      bgcolor: index === stepIndex ? tokens.red : 'action.disabledBackground',
                      transition: reduceMotion ? 'none' : 'width 160ms ease, background-color 160ms ease'
                    }}
                  />
                ))}
              </Stack>

              <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={1} flexWrap="wrap" useFlexGap>
                <Button size="small" color="inherit" onClick={() => finishTour('dismissed')}>
                  Пропустить
                </Button>
                <Stack direction="row" spacing={1}>
                  {stepIndex > 0 && (
                    <Button size="small" variant="outlined" onClick={() => setSelectedStep(targets[stepIndex - 1].step)}>
                      Назад
                    </Button>
                  )}
                  {stepIndex < targets.length - 1 ? (
                    <Button size="small" variant="contained" onClick={() => setSelectedStep(targets[stepIndex + 1].step)}>
                      Далее
                    </Button>
                  ) : (
                    <Button size="small" variant="contained" onClick={() => finishTour('completed')}>
                      Готово
                    </Button>
                  )}
                </Stack>
              </Stack>
            </>
          ) : (
            <CircularProgress size={24} aria-label="Загрузка" />
          )}
        </Paper>
      </Box>
    </Modal>
  );
}

/** Compact header "?" trigger opening contextual tour controls without leaving the current page. */
export function OnboardingHeaderMenu() {
  const ctx = useContext(OnboardingContext);
  const [anchorEl, setAnchorEl] = useState<HTMLElement | null>(null);
  if (!ctx) return null;

  return (
    <>
      <Tooltip title="Обучение и гид по кабинету">
        <IconButton
          aria-label="Обучение"
          data-tour="training-trigger"
          aria-haspopup="menu"
          aria-expanded={Boolean(anchorEl)}
          onClick={(event) => setAnchorEl(event.currentTarget)}
          sx={{ color: '#fff' }}
        >
          <HelpOutlineRoundedIcon />
        </IconButton>
      </Tooltip>
      <Menu
        anchorEl={anchorEl}
        open={Boolean(anchorEl)}
        onClose={() => setAnchorEl(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
      >
        {ctx.hasPageTour && (
          <MenuItem
            onClick={() => {
              setAnchorEl(null);
              ctx.startTour(anchorEl ?? undefined);
            }}
          >
            <ListItemIcon><PlayArrowRoundedIcon fontSize="small" /></ListItemIcon>
            <ListItemText primary="Гид по этой странице" secondary="Подсветка блоков и действий экрана" />
          </MenuItem>
        )}
        <MenuItem
          onClick={() => {
            setAnchorEl(null);
            ctx.startCabinetTour(anchorEl ?? undefined);
          }}
        >
          <ListItemIcon><SchoolOutlinedIcon fontSize="small" /></ListItemIcon>
          <ListItemText primary="Пройти интерактивный гид" secondary="Обзор разделов кабинета" />
        </MenuItem>
        <MenuItem
          onClick={() => {
            setAnchorEl(null);
            ctx.resetTour();
          }}
        >
          <ListItemIcon><RestartAltRoundedIcon fontSize="small" /></ListItemIcon>
          <ListItemText primary="Показать приглашение снова" />
        </MenuItem>
      </Menu>
    </>
  );
}

/** Non-blocking first-visit invitation on the role's home page and on key screens with a page tour. */
export function OnboardingWelcomeBanner() {
  const ctx = useContext(OnboardingContext);
  if (!ctx || !ctx.bannerVisible) return null;

  return (
    <Paper
      data-testid="onboarding-welcome-banner"
      variant="outlined"
      sx={{
        mb: { xs: 2, sm: 3 },
        p: { xs: 1.5, sm: 2 },
        borderRadius: 3,
        borderColor: 'divider',
        bgcolor: 'background.paper'
      }}
    >
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={1.25}
        alignItems={{ xs: 'flex-start', sm: 'center' }}
        justifyContent="space-between"
      >
        <Stack direction="row" spacing={1.5} alignItems="flex-start" sx={{ minWidth: 0 }}>
          <Box
            sx={{
              width: 36,
              height: 36,
              borderRadius: 2,
              bgcolor: 'rgba(239, 49, 36, 0.12)',
              color: tokens.red,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              flexShrink: 0,
              mt: 0.25
            }}
          >
            <SchoolOutlinedIcon fontSize="small" />
          </Box>
          <Box sx={{ minWidth: 0 }}>
            <Typography variant="subtitle1" fontWeight={700} sx={{ overflowWrap: 'anywhere' }}>
              {ctx.welcomeTitle}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ overflowWrap: 'anywhere', display: { xs: 'none', sm: 'block' } }}>
              {ctx.welcomeSubtitle}
            </Typography>
          </Box>
        </Stack>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ flexShrink: 0 }}>
          <Button variant="outlined" size="small" onClick={(event) => ctx.startTour(event.currentTarget)}>
            Начать обучение
          </Button>
          <Button variant="text" size="small" color="inherit" onClick={ctx.dismissBanner}>
            Скрыть
          </Button>
        </Stack>
      </Stack>
    </Paper>
  );
}
