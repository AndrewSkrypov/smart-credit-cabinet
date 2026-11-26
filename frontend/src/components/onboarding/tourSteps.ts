import { Role } from '../../types';

export interface TourStep {
  id: string;
  selector: string;
  badge: string;
  title: string;
  description: string;
}

export interface RoleTourConfig {
  welcomeTitle: string;
  welcomeSubtitle: string;
  steps: TourStep[];
}

export type OnboardingState = 'completed' | 'dismissed' | null;

const STORAGE_PREFIX = 'cabinet.onboarding.v1';

export function onboardingStorageKey(userId: string, role: Role): string {
  return `${STORAGE_PREFIX}:${userId}:${role}`;
}

export function readOnboardingState(userId: string, role: Role): OnboardingState {
  try {
    const raw = window.localStorage.getItem(onboardingStorageKey(userId, role));
    if (raw === 'completed' || raw === 'dismissed') return raw;
    return null;
  } catch {
    return null;
  }
}

export function writeOnboardingState(userId: string, role: Role, state: OnboardingState): void {
  try {
    const key = onboardingStorageKey(userId, role);
    if (state === null) {
      window.localStorage.removeItem(key);
    } else {
      window.localStorage.setItem(key, state);
    }
  } catch {
    // Ignore storage errors in restricted browsing modes.
  }
}

export const roleTours: Record<Role, RoleTourConfig> = {
  AGENT: {
    welcomeTitle: 'Впервые в кабинете агента?',
    welcomeSubtitle: 'За 30 секунд покажем портфель клиентов, предодобренные предложения, расчёт комиссий и границы полномочий.',
    steps: [
      {
        id: 'agent-kpi',
        selector: '[data-tour="agent-kpi"]',
        badge: 'Портфель и комиссия',
        title: 'Сводка по клиентам и вознаграждению',
        description: 'Здесь собраны активные компании, заявки в работе и комиссия: ожидаемая — по открытым заявкам, начисленная — сразу после выдачи кредита.'
      },
      {
        id: 'agent-clients',
        selector: '[data-tour="agent-clients"]',
        badge: 'Клиенты и предодобрения',
        title: 'Закреплённые компании и персональные лимиты',
        description: 'Вы видите только действующих клиентов с активным мандатом. Если у компании есть предодобренное предложение банка, заявку можно запустить в один клик.'
      },
      {
        id: 'agent-applications',
        selector: '[data-tour="agent-applications"]',
        badge: 'Границы полномочий',
        title: 'Заявки портфеля и следующий шаг',
        description: 'Агент заполняет черновик, загружает документы и ведёт переписку. Согласие БКИ, акцепт оффера и подписание ПЭП выполняет строго клиент в своём кабинете.'
      },
      {
        id: 'agent-notifications',
        selector: '[data-tour="notifications-bell"]',
        badge: 'Уведомления',
        title: 'События по вашим заявкам',
        description: 'Когда клиент подтвердит заявку, банк запросит документы или выдаст кредит, уведомление сразу появится здесь.'
      },
      {
        id: 'agent-profile',
        selector: '[data-tour="profile-btn"]',
        badge: 'Профиль',
        title: 'Настройки учётной записи',
        description: 'В профиле можно сменить пароль, настроить уведомления и завершить сессии на всех устройствах.'
      }
    ]
  },
  CLIENT: {
    welcomeTitle: 'Впервые в кредитном кабинете?',
    welcomeSubtitle: 'За 30 секунд покажем, как подобрать кредит, подтвердить заявку от агента и отслеживать решение банка.',
    steps: [
      {
        id: 'client-new',
        selector: '[data-tour="client-new-btn"]',
        badge: 'Быстрый старт',
        title: 'Мастер подбора финансирования',
        description: 'Укажите цель, сумму и срок — кабинет сам подберёт подходящие продукты, рассчитает платёж и подтянет реквизиты компании по ИНН.'
      },
      {
        id: 'client-preapproved',
        selector: '[data-tour="client-preapproved"]',
        badge: 'Предодобрение',
        title: 'Персональные предложения банка',
        description: 'Если банк уже рассчитал лимит по оборотам компании, здесь отображаются готовые условия с упрощённым одобрением в один клик.'
      },
      {
        id: 'client-applications',
        selector: '[data-tour="client-applications"]',
        badge: 'Заявки и обслуживание',
        title: 'Статусы, подтверждения и выданные кредиты',
        description: 'Здесь видны заявки, подготовленные агентом на ваше подтверждение, шкала этапов рассмотрения, а после выдачи — транши и график платежей.'
      },
      {
        id: 'client-notifications',
        selector: '[data-tour="notifications-bell"]',
        badge: 'Уведомления',
        title: 'Важные шаги без звонков и SMS',
        description: 'Запросы документов, решения банка и коды подписания ПЭП приходят прямо внутрь кабинета.'
      },
      {
        id: 'client-nav',
        selector: '[data-tour="nav-tabs"]',
        badge: 'Команда',
        title: 'Управление сотрудниками компании',
        description: 'Во вкладке «Сотрудники» можно пригласить бухгалтера или менеджера с правом подготовки заявок, оставив право подписи за руководителем.'
      }
    ]
  },
  MANAGER: {
    welcomeTitle: 'Гид по рабочему месту менеджера',
    welcomeSubtitle: 'Короткий обзор очереди заявок, агентских бейджей и инструментов дозапроса документов.',
    steps: [
      {
        id: 'manager-queue',
        selector: '[data-tour="bank-queue-tabs"]',
        badge: 'Очередь',
        title: 'Фильтрация и контроль SLA',
        description: 'Сверху всегда заявки, которые ждут решения дольше всех. Заявки, пришедшие через партнёров, отмечены бейджем «Агентский канал».'
      },
      {
        id: 'manager-nav',
        selector: '[data-tour="nav-tabs"]',
        badge: 'Разделы банка',
        title: 'Аналитика, правила и продукты',
        description: 'Переключайтесь между очередью, воронкой аналитики, действующими правилами политики и линейкой кредитных продуктов.'
      },
      {
        id: 'manager-notifications',
        selector: '[data-tour="notifications-bell"]',
        badge: 'Уведомления',
        title: 'Ответы клиентов и агентов',
        description: 'Новые сообщения в чате заявки и загруженные по дозапросу документы отображаются в ленте уведомлений.'
      }
    ]
  },
  UNDERWRITER: {
    welcomeTitle: 'Гид по рабочему месту андеррайтера',
    welcomeSubtitle: 'Покажем очередь ручного рассмотрения, SLA и доступ к скорингу и экспертной системе.',
    steps: [
      {
        id: 'uw-queue',
        selector: '[data-tour="bank-queue-tabs"]',
        badge: 'Ручное рассмотрение',
        title: 'Очередь заявок и просрочки SLA',
        description: 'Вкладка «Ждут решения» собирает заявки на ручном рассмотрении. Внутри карточки доступны 8 факторов скоринга, расхождения и подсказки экспертной системы с цепочкой «почему».'
      },
      {
        id: 'uw-nav',
        selector: '[data-tour="nav-tabs"]',
        badge: 'Навигация',
        title: 'Воронка, правила и продукты',
        description: 'Все решения фиксируются со снимком версии правил и продукта — историю и конверсию можно проверить в разделе «Аналитика».'
      },
      {
        id: 'uw-notifications',
        selector: '[data-tour="notifications-bell"]',
        badge: 'Уведомления',
        title: 'Новые заявки и ответы в чате',
        description: 'При поступлении заявки на ручное рассмотрение или ответа от клиента/агента здесь сразу появляется ссылка на карточку.'
      }
    ]
  },
  RISK_ADMIN: {
    welcomeTitle: 'Гид риск-администратора',
    welcomeSubtitle: 'Быстрый обзор конструктора продуктов, правил кредитной политики и аналитики воронки.',
    steps: [
      {
        id: 'risk-nav',
        selector: '[data-tour="nav-tabs"]',
        badge: 'Управление',
        title: 'Продукты, правила и аналитика',
        description: 'В «Продуктах» вы управляете жизненным циклом (Черновик → Активен → Архив), сроками действия и комиссией агента; в «Правилах» — порогами скоринга.'
      },
      {
        id: 'risk-queue',
        selector: '[data-tour="bank-queue-tabs"]',
        badge: 'Контроль портфеля',
        title: 'Очередь заявок',
        description: 'Здесь можно просматривать активные и завершённые заявки (включая агентский канал); поданные заявки всегда сохраняют снимок своей версии продукта.'
      },
      {
        id: 'risk-profile',
        selector: '[data-tour="profile-btn"]',
        badge: 'Безопасность',
        title: 'Профиль и сессии',
        description: 'Смена пароля и отзыв всех активных сессий выполняются из профиля.'
      }
    ]
  }
};
