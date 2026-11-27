import { CommissionStatus, LegalForm, Role } from '../types';

export const roleLabels: Record<Role, string> = {
  CLIENT: 'Представитель компании',
  AGENT: 'Агент',
  MANAGER: 'Кредитный менеджер',
  UNDERWRITER: 'Андеррайтер',
  RISK_ADMIN: 'Риск-администратор'
};

export const legalFormLabels: Record<LegalForm, string> = {
  LEGAL_ENTITY: 'Организация',
  ENTREPRENEUR: 'Индивидуальный предприниматель'
};

export const commissionStatusLabels: Record<CommissionStatus, string> = {
  NONE: 'Не начислена',
  EXPECTED: 'Ожидается',
  ACCRUED: 'Начислена',
  CANCELLED: 'Отменена'
};

export const actorTypeLabels: Record<'CLIENT' | 'AGENT' | 'SYSTEM' | 'MANAGER' | 'UNDERWRITER', string> = {
  CLIENT: 'Клиент',
  AGENT: 'Агент',
  MANAGER: 'Кредитный менеджер',
  UNDERWRITER: 'Андеррайтер',
  SYSTEM: 'Система'
};

export const bankRoles: Role[] = ['MANAGER', 'UNDERWRITER', 'RISK_ADMIN'];
