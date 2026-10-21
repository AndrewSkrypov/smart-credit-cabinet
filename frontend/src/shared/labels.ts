import { LegalForm, Role } from '../types';

export const roleLabels: Record<Role, string> = {
  CLIENT: 'Представитель компании',
  MANAGER: 'Кредитный менеджер',
  UNDERWRITER: 'Андеррайтер',
  RISK_ADMIN: 'Риск-администратор'
};

export const legalFormLabels: Record<LegalForm, string> = {
  LEGAL_ENTITY: 'Организация',
  ENTREPRENEUR: 'Индивидуальный предприниматель'
};

export const bankRoles: Role[] = ['MANAGER', 'UNDERWRITER', 'RISK_ADMIN'];
