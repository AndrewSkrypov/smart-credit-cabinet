import type { Application } from '../types';

/** Terms the card should lead with: issued after disbursement, agreed after the client accepts an offer, requested before. */
export function agreedTerms(application: Pick<Application, 'status' | 'amount' | 'termMonths' | 'process'>) {
  const loan = application.process.loan;
  const acceptedOffer = ['SIGNING', 'DISBURSING', 'DISBURSED'].includes(application.status) ? application.process.offer : null;
  const agreed = loan ?? acceptedOffer;
  return {
    label: loan ? 'Выдано' : agreed ? 'Согласовано' : null,
    amount: agreed?.amount ?? application.amount,
    termMonths: agreed?.termMonths ?? application.termMonths,
    changed: !!agreed && (agreed.amount !== application.amount || agreed.termMonths !== application.termMonths),
  };
}
