import { useEffect } from 'react';
import { cabinetPathname } from '../auth/demoAccess';

const CABINET = 'Кабинет предпринимателя';
const WORKSPACE = 'Рабочее место банка';

/** Browser tab title: "<page> · Кабинет предпринимателя" (or "· Рабочее место банка" for staff pages), or the product name alone. */
export function usePageTitle(title?: string) {
  useEffect(() => {
    const product = cabinetPathname(window.location.pathname).startsWith('/bank') ? WORKSPACE : CABINET;
    document.title = title ? `${title} · ${product}` : product;
  }, [title]);
}
