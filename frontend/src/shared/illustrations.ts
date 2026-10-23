import { FinancingPurpose } from '../types';

/** Which 3D illustration (`public/illustrations/<name>.png`) stands for a product or a purpose. */
export const productArt: Record<string, string> = {
  OVERDRAFT: 'hourglass',
  WORKING_CAPITAL: 'cart',
  INVESTMENT: 'factory',
  BUSINESS_CARD: 'credit-card',
  GOV_PROGRAM: 'bank'
};

export const purposeArt: Record<FinancingPurpose, string> = {
  CASH_GAP: 'hourglass',
  CURRENT_EXPENSES: 'receipt',
  WORKING_CAPITAL: 'cart',
  EQUIPMENT: 'factory',
  VEHICLE: 'truck',
  RENOVATION: 'construction',
  BUSINESS_DEVELOPMENT: 'rocket'
};
