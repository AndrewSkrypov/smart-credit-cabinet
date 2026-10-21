import { useEffect } from 'react';

const CABINET = 'Кабинет предпринимателя';
const WORKSPACE = 'Рабочее место банка';

/** Browser tab title: "<page> · Кабинет предпринимателя" (or "· Рабочее место банка" for staff pages), or the product name alone. */
export function usePageTitle(title?: string) {
  useEffect(() => {
    const product = window.location.pathname.startsWith('/bank') ? WORKSPACE : CABINET;
    document.title = title ? `${title} · ${product}` : product;
  }, [title]);
}
