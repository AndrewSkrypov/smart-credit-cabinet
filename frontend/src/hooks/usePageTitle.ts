import { useEffect } from 'react';

const PRODUCT = 'Кабинет предпринимателя';

/** Browser tab title: "<page> · Кабинет предпринимателя", or the product name alone. */
export function usePageTitle(title?: string) {
  useEffect(() => {
    document.title = title ? `${title} · ${PRODUCT}` : PRODUCT;
  }, [title]);
}
