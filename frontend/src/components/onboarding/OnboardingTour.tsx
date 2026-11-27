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
  OnboardingState,
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
  startTour: (trigger?: HTMLElement) => void;
  dismissBanner: () => void;
  resetTour: () => void;
}

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
  const config = roleTours[role];
  const homePath = roleHome[role];

  const [status, setStatus] = useState<OnboardingState>(() =>
    userId ? readOnboardingState(userId, role) : 'dismissed'
  );
  const [open, setOpen] = useState(false);
  const [activeSteps, setActiveSteps] = useState<TourStep[]>([]);
  const [stepIndex, setStepIndex] = useState(0);
  const [targetRect, setTargetRect] = useState<SpotlightRect | null>(null);
  const pendingStartRef = useRef(false);
  const returnFocusRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!userId) return;
    setStatus(readOnboardingState(userId, role));
    setOpen(false);
    setStepIndex(0);
    setActiveSteps([]);
    setTargetRect(null);
    pendingStartRef.current = false;
  }, [userId, role]);

  const launchOnCurrentPage = useCallback(() => {
    const resolved = config.steps.filter((step) => findVisibleTarget(step.selector) !== null);
    const stepsToUse = resolved.length > 0 ? resolved : config.steps;
    setActiveSteps(stepsToUse);
    setStepIndex(0);
    setOpen(true);
  }, [config.steps]);

  const startTour = useCallback((trigger?: HTMLElement) => {
    returnFocusRef.current = trigger ?? null;
    if (location.pathname !== homePath) {
      pendingStartRef.current = true;
      navigate(homePath);
      return;
    }
    launchOnCurrentPage();
  }, [homePath, launchOnCurrentPage, location.pathname, navigate]);

  useEffect(() => {
    if (!pendingStartRef.current || location.pathname !== homePath) return;
    pendingStartRef.current = false;
    const timer = window.setTimeout(() => launchOnCurrentPage(), 120);
    return () => window.clearTimeout(timer);
  }, [homePath, launchOnCurrentPage, location.pathname]);

  const finishTour = useCallback((nextState: 'completed' | 'dismissed') => {
    setOpen(false);
    setTargetRect(null);
    if (userId) {
      writeOnboardingState(userId, role, nextState);
      setStatus(nextState);
    }
    const trigger = returnFocusRef.current;
    window.requestAnimationFrame(() => {
      const target = trigger?.isConnected ? trigger : document.querySelector<HTMLElement>('[data-tour="training-trigger"]');
      target?.focus();
    });
  }, [role, userId]);

  const dismissBanner = useCallback(() => {
    if (userId) {
      writeOnboardingState(userId, role, 'dismissed');
      setStatus('dismissed');
    }
  }, [role, userId]);

  const resetTour = useCallback(() => {
    if (userId) {
      writeOnboardingState(userId, role, null);
      setStatus(null);
    }
  }, [role, userId]);

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
    if (target && typeof target.scrollIntoView === 'function') {
      target.scrollIntoView({ behavior: reduceMotion ? 'instant' : 'smooth', block: 'center', inline: 'nearest' });
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
  }, [currentStep, measureCurrentTarget, open, reduceMotion]);

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

  const bannerVisible = Boolean(userId) && status === null && !open && location.pathname === homePath;

  const value = useMemo<OnboardingContextValue>(() => ({
    open,
    bannerVisible,
    welcomeTitle: config.welcomeTitle,
    welcomeSubtitle: config.welcomeSubtitle,
    startTour,
    dismissBanner,
    resetTour
  }), [bannerVisible, config.welcomeSubtitle, config.welcomeTitle, dismissBanner, open, resetTour, startTour]);

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
        <MenuItem
          onClick={() => {
            setAnchorEl(null);
            ctx.startTour(anchorEl ?? undefined);
          }}
        >
          <ListItemIcon><PlayArrowRoundedIcon fontSize="small" /></ListItemIcon>
          <ListItemText primary="Пройти интерактивный гид" secondary="Подсветка разделов и действий" />
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

/** Non-blocking first-visit invitation banner on the role's home page. */
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
