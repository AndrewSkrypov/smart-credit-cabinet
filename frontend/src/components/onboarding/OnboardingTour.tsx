import {
  Box,
  Button,
  Chip,
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
import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { roleHome, useAuth } from '../../auth/AuthProvider';
import { tokens } from '../../theme/theme';
import {
  findPageTour,
  OnboardingState,
  pageTourIds,
  readOnboardingState,
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

interface OnboardingContextValue {
  open: boolean;
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

function findVisibleTarget(selector: string): HTMLElement | null {
  const candidates = document.querySelectorAll<HTMLElement>(selector);
  for (const el of candidates) {
    const style = window.getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden') continue;
    const rect = el.getBoundingClientRect();
    if (rect.width > 0 && rect.height > 0) return el;
  }
  return null;
}

export function OnboardingProvider({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const theme = useTheme();
  const isMobile = useMediaQuery(theme.breakpoints.down('sm'));
  const reduceMotion = useMediaQuery('(prefers-reduced-motion: reduce)');

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
  const [open, setOpen] = useState(false);
  const [activeSteps, setActiveSteps] = useState<TourStep[]>([]);
  const [stepIndex, setStepIndex] = useState(0);
  const [targetRect, setTargetRect] = useState<SpotlightRect | null>(null);
  const pendingCabinetStartRef = useRef(false);
  const runningTourIdRef = useRef<string | undefined>(undefined);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    setOpen(false);
    setStepIndex(0);
    setActiveSteps([]);
    setTargetRect(null);
    pendingCabinetStartRef.current = false;
  }, [userId, role]);

  useEffect(() => {
    setStatus(userId && screenConfig ? readOnboardingState(userId, role, screenTourId) : 'dismissed');
  }, [role, screenConfig, screenTourId, userId]);

  const launch = useCallback((steps: TourStep[], tourId: string | undefined) => {
    const resolved = steps.filter((step) => findVisibleTarget(step.selector) !== null);
    runningTourIdRef.current = tourId;
    setActiveSteps(resolved.length > 0 ? resolved : steps);
    setStepIndex(0);
    setOpen(true);
  }, []);

  const startCabinetTour = useCallback((trigger?: HTMLElement) => {
    returnFocusRef.current = trigger ?? null;
    if (!onHome) {
      pendingCabinetStartRef.current = true;
      navigate(homePath);
      return;
    }
    launch(homeConfig.steps, undefined);
  }, [homeConfig.steps, homePath, launch, navigate, onHome]);

  const startTour = useCallback((trigger?: HTMLElement) => {
    if (!pageTour) {
      startCabinetTour(trigger);
      return;
    }
    returnFocusRef.current = trigger ?? null;
    launch(pageTour.steps, pageTour.id);
  }, [launch, pageTour, startCabinetTour]);

  useEffect(() => {
    if (!pendingCabinetStartRef.current || !onHome) return;
    pendingCabinetStartRef.current = false;
    const timer = window.setTimeout(() => launch(homeConfig.steps, undefined), 120);
    return () => window.clearTimeout(timer);
  }, [homeConfig.steps, launch, onHome]);

  const finishTour = useCallback((nextState: 'completed' | 'dismissed') => {
    setOpen(false);
    setTargetRect(null);
    if (userId) {
      const tourId = runningTourIdRef.current;
      writeOnboardingState(userId, role, nextState, tourId);
      if (tourId === screenTourId) setStatus(nextState);
    }
    const trigger = returnFocusRef.current;
    window.requestAnimationFrame(() => {
      const target = trigger?.isConnected ? trigger : document.querySelector<HTMLElement>('[data-tour="training-trigger"]');
      target?.focus();
    });
  }, [role, screenTourId, userId]);

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

  const currentStep = activeSteps[stepIndex] ?? null;

  const measureCurrentTarget = useCallback(() => {
    if (!open || !currentStep) {
      setTargetRect(null);
      return;
    }
    const target = findVisibleTarget(currentStep.selector);
    if (!target) {
      setTargetRect(null);
      return;
    }
    const rect = target.getBoundingClientRect();
    const pad = 6;
    setTargetRect({
      top: Math.max(4, rect.top - pad),
      left: Math.max(4, rect.left - pad),
      width: Math.max(0, rect.width + pad * 2),
      height: Math.max(0, rect.height + pad * 2)
    });
  }, [currentStep, open]);

  useEffect(() => {
    if (!open || !currentStep) return;
    const target = findVisibleTarget(currentStep.selector);
    const behavior = reduceMotion ? 'instant' : 'smooth';
    if (target && isMobile) {
      // On a phone the step card covers the lower part of the screen: put the block right under the header.
      const top = target.getBoundingClientRect().top + window.scrollY - MOBILE_HEADER_OFFSET;
      if (typeof window.scrollTo === 'function') window.scrollTo({ top: Math.max(0, top), behavior });
    } else if (target && typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ behavior, block: 'center', inline: 'nearest' });
    }
    measureCurrentTarget();
    const timer = window.setTimeout(measureCurrentTarget, 220);
    window.addEventListener('resize', measureCurrentTarget);
    window.addEventListener('scroll', measureCurrentTarget, true);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener('resize', measureCurrentTarget);
      window.removeEventListener('scroll', measureCurrentTarget, true);
    };
  }, [currentStep, isMobile, measureCurrentTarget, open, reduceMotion]);

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'ArrowRight') {
        event.preventDefault();
        if (stepIndex < activeSteps.length - 1) setStepIndex((i) => i + 1);
        else finishTour('completed');
      } else if (event.key === 'ArrowLeft') {
        event.preventDefault();
        if (stepIndex > 0) setStepIndex((i) => i - 1);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [activeSteps.length, finishTour, open, stepIndex]);

  const bannerVisible = Boolean(userId) && Boolean(screenConfig) && status === null && !open;

  const value = useMemo<OnboardingContextValue>(() => ({
    open,
    bannerVisible,
    welcomeTitle: screenConfig?.welcomeTitle ?? homeConfig.welcomeTitle,
    welcomeSubtitle: screenConfig?.welcomeSubtitle ?? homeConfig.welcomeSubtitle,
    hasPageTour: Boolean(pageTour),
    startTour,
    startCabinetTour,
    dismissBanner,
    resetTour
  }), [bannerVisible, dismissBanner, homeConfig, open, pageTour, resetTour, screenConfig, startCabinetTour, startTour]);

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
    <OnboardingContext.Provider value={value}>
      {children}
      {open && currentStep && (
        <Modal open hideBackdrop disableScrollLock disableRestoreFocus onClose={() => finishTour('dismissed')}>
        <Box role="dialog" aria-modal="true" tabIndex={-1} sx={{ position: 'fixed', inset: 0, outline: 'none' }}
          aria-label={`Шаг ${stepIndex + 1} из ${activeSteps.length}: ${currentStep.title}`}>
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
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Chip size="small" color="error" variant="outlined" label={currentStep.badge} />
                <Typography variant="caption" color="text.secondary" fontWeight={600}>
                  Шаг {stepIndex + 1} из {activeSteps.length}
                </Typography>
              </Stack>
              <IconButton autoFocus size="small" aria-label="Закрыть обучение" onClick={() => finishTour('dismissed')}>
                <CloseRoundedIcon fontSize="small" />
              </IconButton>
            </Stack>

            <Typography variant="subtitle1" fontWeight={700} sx={{ mb: 0.75, overflowWrap: 'anywhere' }}>
              {currentStep.title}
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{ mb: 2, lineHeight: 1.55, overflowWrap: 'anywhere' }}>
              {currentStep.description}
            </Typography>

            <Stack direction="row" spacing={0.75} sx={{ mb: 2 }} role="group" aria-label="Шаги обучения">
              {activeSteps.map((step, index) => (
                <Box
                  key={step.id}
                  component="button"
                  type="button"
                  aria-label={`Перейти к шагу ${index + 1}`}
                  aria-current={index === stepIndex ? 'step' : undefined}
                  onClick={() => setStepIndex(index)}
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
                  <Button size="small" variant="outlined" onClick={() => setStepIndex((i) => i - 1)}>
                    Назад
                  </Button>
                )}
                {stepIndex < activeSteps.length - 1 ? (
                  <Button size="small" variant="contained" onClick={() => setStepIndex((i) => i + 1)}>
                    Далее
                  </Button>
                ) : (
                  <Button size="small" variant="contained" onClick={() => finishTour('completed')}>
                    Готово
                  </Button>
                )}
              </Stack>
            </Stack>
          </Paper>
        </Box>
        </Modal>
      )}
    </OnboardingContext.Provider>
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
