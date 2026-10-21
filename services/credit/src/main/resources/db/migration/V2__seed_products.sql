-- The product line of the prototype. Parameters are synthetic but plausible for the market;
-- the risk administrator changes them in the interface (docs/process-model.md, section 5).
insert into products (code, name, description, purposes, min_amount, max_amount, min_term_months, max_term_months,
                      base_rate, auto_approval_limit, revolving, display_order) values
('OVERDRAFT', 'Овердрафт',
 'Деньги на счёте, когда своих не хватает: платежи проходят, даже если поступления задерживаются. Проценты — только за дни, когда лимит использован.',
 '{CASH_GAP,CURRENT_EXPENSES}', 100000, 5000000, 12, 12, 19.00, 2000000, true, 10),
('WORKING_CAPITAL', 'Кредит на пополнение оборота',
 'Закупка товара и сырья, подготовка к сезону, крупный заказ. Погашение равными платежами.',
 '{WORKING_CAPITAL,CASH_GAP,BUSINESS_DEVELOPMENT}', 300000, 30000000, 3, 36, 20.00, 5000000, false, 20),
('INVESTMENT', 'Инвестиционный кредит',
 'Оборудование, транспорт, ремонт помещения. Длинный срок снижает ежемесячный платёж.',
 '{EQUIPMENT,VEHICLE,RENOVATION,BUSINESS_DEVELOPMENT}', 1000000, 50000000, 12, 60, 18.00, 5000000, false, 30),
('BUSINESS_CARD', 'Кредитная бизнес-карта',
 'Текущие расходы и небольшие закупки картой. Лимит восстанавливается после погашения.',
 '{CURRENT_EXPENSES,CASH_GAP}', 50000, 1000000, 12, 12, 25.00, 1000000, true, 40),
('GOV_PROGRAM', 'Кредит по госпрограмме поддержки МСП',
 'Сниженная ставка для приоритетных отраслей: производство, сельское хозяйство, IT, туризм и другие. Доступность проверяется по ОКВЭД.',
 '{WORKING_CAPITAL,EQUIPMENT,BUSINESS_DEVELOPMENT}', 500000, 30000000, 12, 36, 12.00, 3000000, false, 50);
