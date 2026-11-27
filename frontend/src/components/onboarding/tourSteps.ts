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
  readyStepId: string;
  steps: TourStep[];
}

export type OnboardingState = 'completed' | 'dismissed' | null;

const STORAGE_PREFIX = 'cabinet.onboarding.v1';

/** The cabinet tour keeps the original key; a page tour adds its own id to it. */
export function onboardingStorageKey(userId: string, role: Role, tourId?: string): string {
  const base = `${STORAGE_PREFIX}:${userId}:${role}`;
  return tourId ? `${base}:${tourId}` : base;
}

export function readOnboardingState(userId: string, role: Role, tourId?: string): OnboardingState {
  try {
    const raw = window.localStorage.getItem(onboardingStorageKey(userId, role, tourId));
    if (raw === 'completed' || raw === 'dismissed') return raw;
    return null;
  } catch {
    return null;
  }
}

export function writeOnboardingState(userId: string, role: Role, state: OnboardingState, tourId?: string): void {
  try {
    const key = onboardingStorageKey(userId, role, tourId);
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
    readyStepId: 'agent-kpi',
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
    readyStepId: 'client-applications',
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
        description: 'Запросы документов, решения банка и сообщения агента приходят прямо в кабинет — без звонков, почты и SMS. Код подписания показывается в карточке заявки на шаге подписания.'
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
    readyStepId: 'manager-queue',
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
    readyStepId: 'uw-queue',
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
    readyStepId: 'risk-queue',
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

const BANK_STAFF: Role[] = ['MANAGER', 'UNDERWRITER', 'RISK_ADMIN'];

export interface PageTourConfig extends RoleTourConfig {
  id: string;
  roles: Role[];
  path: RegExp;
}

/**
 * Tours of the key working screens. Every step is optional: the tour shows only the blocks
 * the current application status has on screen.
 */
export const pageTours: PageTourConfig[] = [
  {
    id: 'client-application',
    readyStepId: 'app-status',
    roles: ['CLIENT'],
    path: /^\/client\/applications\/[^/]+\/?$/,
    welcomeTitle: 'Впервые в карточке заявки?',
    welcomeSubtitle: 'Покажем, где видно этап рассмотрения, что требуется от вас и где подтверждать, подписывать и обслуживать кредит.',
    steps: [
      {
        id: 'app-status',
        selector: '[data-tour="app-status"]',
        badge: 'Этап',
        title: 'Где сейчас заявка и что нужно от вас',
        description: 'Шкала показывает путь от черновика до выдачи. Ниже — что делает банк прямо сейчас и есть ли действие за вами.'
      },
      {
        id: 'app-confirm',
        selector: '[data-tour="app-confirm"]',
        badge: 'Агентский канал',
        title: 'Проверьте заявку, подготовленную агентом',
        description: 'Агент заполнил заявку и приложил документы, но отправить её в банк можете только вы: проверьте условия, дайте согласие на запрос в БКИ или верните заявку агенту с комментарием.'
      },
      {
        id: 'app-info',
        selector: '[data-tour="app-info"]',
        badge: 'Запрос банка',
        title: 'Банк просит дополнить заявку',
        description: 'Здесь перечислено, каких документов не хватает, и срок ответа. Загрузите файлы — заявка сама вернётся на рассмотрение.'
      },
      {
        id: 'app-offer',
        selector: '[data-tour="app-offer"]',
        badge: 'Предложение',
        title: 'Сравните запрошенное и предложенное',
        description: 'Банк показывает свои условия рядом с вашими: сумму, срок, ставку и платёж. Принять или отклонить предложение решаете только вы.'
      },
      {
        id: 'app-signing',
        selector: '[data-tour="app-signing"]',
        badge: 'Подписание',
        title: 'Подпись кодом',
        description: 'В прототипе код ПЭП показывается здесь же вместо SMS. Подписать может сотрудник с правом подписи.'
      },
      {
        id: 'app-loan',
        selector: '[data-tour="app-loan"]',
        badge: 'Обслуживание',
        title: 'Кредит после выдачи',
        description: 'График платежей, транши кредитной линии, очередной платёж и досрочное погашение — всё в карточке, до статуса «Погашен».'
      },
      {
        id: 'app-documents',
        selector: '[data-tour="app-documents"]',
        badge: 'Документы',
        title: 'Документы заявки',
        description: 'Файлы проверяются на формат и антивирусом. Скачать их могут только сотрудники вашей компании, агент по мандату и банк.'
      },
      {
        id: 'app-messages',
        selector: '[data-tour="app-messages"]',
        badge: 'Переписка',
        title: 'Вопросы банку по этой заявке',
        description: 'Переписка привязана к заявке и сохраняется в истории — не нужно звонить или писать на почту.'
      },
      {
        id: 'app-history',
        selector: '[data-tour="app-history"]',
        badge: 'История',
        title: 'Кто и когда менял заявку',
        description: 'Каждый шаг клиента, агента и банка записан с временем — на случай вопросов к рассмотрению.'
      }
    ]
  },
  {
    id: 'agent-application',
    readyStepId: 'agent-app-status',
    roles: ['AGENT'],
    path: /^\/agent\/applications\/[^/]+\/?$/,
    welcomeTitle: 'Как вести заявку клиента',
    welcomeSubtitle: 'Что делает агент, что остаётся за клиентом и банком и где видно следующий шаг.',
    steps: [
      {
        id: 'agent-app-status',
        selector: '[data-tour="agent-app-status"]',
        badge: 'Следующий шаг',
        title: 'Этап и кто сейчас действует',
        description: 'Если следующий шаг за вами, он подсвечен. Если ждём клиента или банк — видно, чего именно.'
      },
      {
        id: 'agent-app-draft',
        selector: '[data-tour="agent-app-draft"]',
        badge: 'Черновик',
        title: 'Заполните заявку за клиента',
        description: 'Продукт, сумму, срок и цель вы готовите сами. Отправить в банк может только клиент — после проверки и согласия на запрос в БКИ.'
      },
      {
        id: 'agent-app-info',
        selector: '[data-tour="agent-app-info"]',
        badge: 'Дозапрос',
        title: 'Ответьте на запрос банка',
        description: 'Банк попросил документы — загрузите их до срока, и заявка вернётся на рассмотрение.'
      },
      {
        id: 'agent-app-offer',
        selector: '[data-tour="agent-app-offer"]',
        badge: 'Предложение',
        title: 'Условия банка — для разговора с клиентом',
        description: 'Вы видите одобренные условия и срок действия. Принимает предложение и подписывает договор клиент в своём кабинете.'
      },
      {
        id: 'agent-app-loan',
        selector: '[data-tour="agent-app-loan"]',
        badge: 'После выдачи',
        title: 'Кредит клиента только для просмотра',
        description: 'График и транши видны, чтобы напомнить клиенту о платеже. Платить и выбирать транши агент не может.'
      },
      {
        id: 'agent-app-documents',
        selector: '[data-tour="agent-app-documents"]',
        badge: 'Документы',
        title: 'Документы по мандату',
        description: 'Загружайте документы клиента в черновик. Чужие компании и их файлы вам недоступны.'
      },
      {
        id: 'agent-app-messages',
        selector: '[data-tour="agent-app-messages"]',
        badge: 'Переписка',
        title: 'Вопросы банку',
        description: 'Переписка общая для клиента, агента и банка и остаётся в истории заявки.'
      },
      {
        id: 'agent-app-summary',
        selector: '[data-tour="agent-app-summary"]',
        badge: 'Сводка',
        title: 'Клиент и ожидаемая комиссия',
        description: 'Параметры заявки и вознаграждение: ожидаемое по открытой заявке, начисленное — после выдачи кредита.'
      }
    ]
  },
  {
    id: 'agent-client',
    readyStepId: 'agent-client-applications',
    roles: ['AGENT'],
    path: /^\/agent\/clients\/[^/]+\/?$/,
    welcomeTitle: 'Карточка клиента',
    welcomeSubtitle: 'Предодобренные предложения и заявки одной компании в одном месте.',
    steps: [
      {
        id: 'agent-client-offers',
        selector: '[data-tour="agent-client-offers"]',
        badge: 'Предодобрение',
        title: 'Персональные предложения банка',
        description: 'Если банк уже рассчитал лимит, черновик по предложению создаётся в один клик с готовыми условиями.'
      },
      {
        id: 'agent-client-new',
        selector: '[data-tour="agent-client-new"]',
        badge: 'Новая заявка',
        title: 'Черновик за клиента',
        description: 'Реквизиты компании уже известны банку — укажите продукт, сумму, срок и цель. Черновик не виден клиенту, пока вы не отправите его на подтверждение.'
      },
      {
        id: 'agent-client-applications',
        selector: '[data-tour="agent-client-applications"]',
        badge: 'Заявки',
        title: 'Заявки компании',
        description: 'Все заявки клиента, оформленные через вас, с текущим статусом и следующим шагом.'
      }
    ]
  },
  {
    id: 'bank-application',
    readyStepId: 'bank-app-summary',
    roles: BANK_STAFF,
    path: /^\/bank\/applications\/[^/]+\/?$/,
    welcomeTitle: 'Как читать карточку оценки',
    welcomeSubtitle: 'Где решение, подсказки экспертной системы, скоринг и данные источников.',
    steps: [
      {
        id: 'bank-app-summary',
        selector: '[data-tour="bank-app-summary"]',
        badge: 'Сводка',
        title: 'Главное о заявке',
        description: 'Краткий итог: что просит клиент, где заявка сейчас, последнее решение и следующий шаг. Бейдж «Агентский канал» в шапке показывает, что заявку готовил агент.'
      },
      {
        id: 'bank-app-decision',
        selector: '[data-tour="bank-app-decision"]',
        badge: 'Решение',
        title: 'Решение по заявке и его обоснование',
        description: 'Одобрить, предложить другие условия, запросить документы или отказать. Решение против рекомендации требует обоснования и попадает в журнал.'
      },
      {
        id: 'bank-app-advice',
        selector: '[data-tour="bank-app-advice"]',
        badge: 'Экспертная система',
        title: 'Что проверить и почему',
        description: 'Подсказки сети правил с цепочкой «почему». Это помощь специалисту, а не решение: решает банк.'
      },
      {
        id: 'bank-app-score',
        selector: '[data-tour="bank-app-score"]',
        badge: 'Скоринг',
        title: 'Восемь факторов оценки',
        description: 'Вклад каждого фактора и версия правил, по которым считалось. Решение воспроизводимо.'
      },
      {
        id: 'bank-app-discrepancies',
        selector: '[data-tour="bank-app-discrepancies"]',
        badge: 'Расхождения',
        title: 'Слова клиента против данных',
        description: 'Где заявленная выручка или сведения расходятся с ФНС, реестром и бюро.'
      },
      {
        id: 'bank-app-sources',
        selector: '[data-tour="bank-app-sources"]',
        badge: 'Источники',
        title: 'ЕГРЮЛ, ФНС, БКИ и обороты',
        description: 'Ответы внешних источников со временем получения. В прототипе это симуляторы.'
      },
      {
        id: 'bank-app-audit',
        selector: '[data-tour="bank-app-audit"]',
        badge: 'Журнал',
        title: 'Журнал действий',
        description: 'Все переходы, решения и действия клиента и агента с временем и исполнителем.'
      }
    ]
  },
  {
    id: 'bank-products',
    readyStepId: 'products-select',
    roles: BANK_STAFF,
    path: /^\/bank\/products\/?$/,
    welcomeTitle: 'Конструктор продуктов',
    welcomeSubtitle: 'Черновик, публикация, архив и версии условий — что видят клиенты и что остаётся за поданными заявками.',
    steps: [
      {
        id: 'products-create',
        selector: '[data-tour="products-create"]',
        badge: 'Новый продукт',
        title: 'Начните с черновика',
        description: 'Новый продукт создаётся в статусе «Черновик» и не виден клиентам, пока его не опубликуют.'
      },
      {
        id: 'products-select',
        selector: '[data-tour="products-select"]',
        badge: 'Каталог',
        title: 'Выберите продукт',
        description: 'В списке у каждого продукта указан статус: черновик, активен или в архиве.'
      },
      {
        id: 'products-status',
        selector: '[data-tour="products-status"]',
        badge: 'Версия',
        title: 'Версия, статус и комиссия агента',
        description: 'Каждое изменение условий — новая версия. Поданные заявки сохраняют ту версию, с которой их подали.'
      },
      {
        id: 'products-lifecycle',
        selector: '[data-tour="products-lifecycle"]',
        badge: 'Жизненный цикл',
        title: 'Опубликовать, архивировать, восстановить',
        description: 'Смена статуса требует причины. Архивный продукт пропадает из каталога, но действующие заявки продолжают работать по своим условиям.'
      },
      {
        id: 'products-history',
        selector: '[data-tour="products-history"]',
        badge: 'История',
        title: 'Кто и что менял',
        description: 'Версии с автором, причиной и сравнением условий.'
      }
    ]
  }
];

export function findPageTour(role: Role, pathname: string): PageTourConfig | null {
  return pageTours.find((tour) => tour.roles.includes(role) && tour.path.test(pathname)) ?? null;
}

export function pageTourIds(role: Role): string[] {
  return pageTours.filter((tour) => tour.roles.includes(role)).map((tour) => tour.id);
}
