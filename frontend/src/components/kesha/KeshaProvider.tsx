import { createContext, ReactNode, RefObject, useContext, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { useAuth } from '../../auth/AuthProvider';
import { readSession } from '../../auth/session';
import { useOnboardingRunning } from '../onboarding/OnboardingTour';
import { useKeshaEnabled } from './useKeshaEnabled';
import { useKeshaProactivity } from './useKeshaProactivity';

interface KeshaContextValue {
  enabled: boolean;
  proactive: boolean;
  setProactive: (value: boolean) => void;
  blocked: boolean;
  editing: boolean;
  chatOpen: boolean;
  chatHintSlot: HTMLDivElement | null;
  setChatHintSlot: (slot: HTMLDivElement | null) => void;
  returnFromChat: React.MutableRefObject<HTMLElement | null>;
  overlay: HTMLDivElement | null;
  attached: string | null;
  setAttached: React.Dispatch<React.SetStateAction<string | null>>;
  setChatOpen: (open: boolean) => void;
  dockBird: RefObject<HTMLSpanElement>;
  offered: Set<string>;
  isCurrentAccount: () => boolean;
}

const KeshaContext = createContext<KeshaContextValue | null>(null);
const modal = '.MuiModal-root:not(.MuiModal-hidden), [role="dialog"][aria-modal="true"]';
const keepFocus = 'textarea, [role="textbox"], [contenteditable]:not([contenteditable="false"]), input:not([type="checkbox"]):not([type="radio"]):not([type="file"]), [data-kesha-launcher]';

export function useKesha() {
  return useContext(KeshaContext);
}

export function KeshaProvider({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  const flag = useKeshaEnabled();
  const { proactive, setProactive } = useKeshaProactivity();
  const tourRunning = useOnboardingRunning();
  const [sameTabAccount, setSameTabAccount] = useState(true);
  const [chatOpen, setChatOpen] = useState(false);
  const [chatHintSlot, setChatHintSlot] = useState<HTMLDivElement | null>(null);
  const returnFromChat = useRef<HTMLElement | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState(false);
  const [attached, setAttached] = useState<string | null>(null);
  const [overlay, setOverlay] = useState<HTMLDivElement | null>(null);
  const dockBird = useRef<HTMLSpanElement>(null);
  const offered = useRef(new Set<string>());
  const enabled = flag && session?.user.role === 'AGENT' && sameTabAccount;

  function isCurrentAccount() {
    const stored = readSession();
    return session?.user.role === 'AGENT' && stored?.user.role === 'AGENT' &&
      stored.user.id === session.user.id && stored.token === session.token;
  }

  useEffect(() => {
    const update = () => {
      const stored = readSession();
      setSameTabAccount(stored?.user.id === session?.user.id && stored?.user.role === session?.user.role && stored?.token === session?.token);
    };
    update();
    window.addEventListener('storage', update);
    return () => window.removeEventListener('storage', update);
  }, [session]);

  useEffect(() => {
    if (!enabled) return;
    const syncModal = () => setModalOpen(!!document.querySelector(modal));
    const syncFocus = (event?: FocusEvent) => {
      const target = event?.type === 'focusout' ? event.relatedTarget : event?.target ?? document.activeElement;
      setEditing(target instanceof Element && !!target.closest(keepFocus));
    };
    syncModal();
    syncFocus();
    const observer = new MutationObserver(syncModal);
    observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['aria-modal', 'aria-hidden'] });
    document.addEventListener('focusin', syncFocus);
    document.addEventListener('focusout', syncFocus);
    return () => {
      observer.disconnect();
      document.removeEventListener('focusin', syncFocus);
      document.removeEventListener('focusout', syncFocus);
    };
  }, [enabled]);

  return <KeshaContext.Provider value={{
    enabled, proactive, setProactive, blocked: chatOpen || tourRunning || modalOpen, editing, chatOpen, chatHintSlot, setChatHintSlot,
    returnFromChat, overlay, attached, setAttached, setChatOpen,
    dockBird, offered: offered.current, isCurrentAccount
  }}>
    {children}
    {enabled && createPortal(
      <div ref={setOverlay} data-testid="kesha-overlay" style={{ position: 'fixed', inset: 0, pointerEvents: 'none', zIndex: 1200 }} />,
      document.body
    )}
  </KeshaContext.Provider>;
}
