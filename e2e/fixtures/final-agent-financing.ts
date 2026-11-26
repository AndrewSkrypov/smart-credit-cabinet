export const financingFixture = {
  companies: {
    vector: {
      account: 'logistic',
      id: '6f1d4c1e-0b8e-4c55-9a51-000000000003',
      inn: '0001001021',
      name: 'Вектор Логистик',
    },
    furniture: {
      account: 'mebel',
      id: '6f1d4c1e-0b8e-4c55-9a51-000000000002',
      inn: '0001001014',
      name: 'Северная мебель',
    },
    technopark: {
      account: 'technopark',
      id: '6f1d4c1e-0b8e-4c55-9a51-000000000007',
      inn: '0001001046',
      name: 'Технопарк Плюс',
    },
  },
  offer: {
    amount: 2_000_000,
    annualRate: 20,
    monthlyBase: 150_000,
    expectedReceipt: 140_000,
    terms: [12, 24],
    delayedBalanceRubles: { 12: -35_000, 24: 48_000 },
    delayedBalanceToleranceRubles: 1_000,
  },
  reserve: {
    postedReceipt: 140_000,
    withReceipt: 0,
    withoutReceipt: 130_000,
    availableLimit: 80_000,
    uncovered: 50_000,
  },
} as const;
