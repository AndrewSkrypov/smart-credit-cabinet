import { z } from "zod";

export const applicationId = z.uuid();
const instant = z.iso.datetime();
const date = z.iso.date();
const revision = z.string().regex(/^\d+$/);
const decimal = z.string().regex(/^-?\d+(?:\.\d+)?$/);
const positiveInteger = z.number().int().positive();
const delay = z.number().int().min(0).max(365);
const cabinetPath = z.string().regex(/^\/agent\/applications\/[0-9a-f-]{36}(?:#(?:draft-financing|application-info-request))?$/i);
const metadata = { source: z.enum(["synthetic-fixture", "credit"]), asOf: instant };
const item = z.strictObject({
  applicationId, number: z.string().nullable(), companyName: z.string().nullable(),
  status: z.string(), actionOwner: z.enum(["AGENT", "CLIENT", "BANK", "SYSTEM"]).nullable(),
  actionLabel: z.string(), actionDeadline: instant.nullable(), cabinetPath,
});
const basis = z.strictObject({
  applicationVersion: revision, productCode: z.string(), productVersion: revision,
  savedScenarioRevision: revision.nullable(),
});
const inputsUsed = z.strictObject({
  currency: z.literal("RUB"), paymentModel: z.enum(["ANNUITY", "REVOLVING"]),
  loanAmountRub: decimal, annualRatePercent: decimal, baseTermMonths: positiveInteger,
  alternativeTermMonths: positiveInteger, openingBalanceRub: decimal, expectedReceiptAmountRub: decimal,
  expectedReceiptDate: date, paymentDate: date, delayDays: delay, receiptIncludedByPayment: z.boolean(),
  lastIncludedDelayDays: z.number().int().nonnegative().nullable(),
});
const option = z.strictObject({
  termMonths: positiveInteger, monthlyPaymentRub: decimal,
  totalInterestRub: decimal, balanceOnPaymentDateRub: decimal,
});

export const listInput = z.strictObject({ limit: z.number().int().min(1).max(20).default(10) });
export const summaryInput = z.strictObject({ applicationId });
export const previewInput = z.strictObject({ applicationId, delayDays: delay, alternativeTermMonths: positiveInteger });
export const listOutput = z.strictObject({ ...metadata, items: z.array(item).max(20) });
export const summaryOutput = z.strictObject({
  ...metadata, application: item, applicationVersion: revision, savedScenarioRevision: revision.nullable(),
  statementRequirement: z.enum(["OPEN", "SATISFIED", "NOT_REQUESTED"]).nullable(),
});
export const previewOutput = z.strictObject({
  ...metadata, preview: z.literal(true), basis, inputsUsed, options: z.tuple([option, option]), cabinetPath,
});

export type CaseItem = z.infer<typeof item>;
export type ListArgs = z.infer<typeof listInput>;
export type SummaryArgs = z.infer<typeof summaryInput>;
export type PreviewArgs = z.infer<typeof previewInput>;
export type ListResult = z.infer<typeof listOutput>;
export type SummaryResult = z.infer<typeof summaryOutput>;
export type PreviewResult = z.infer<typeof previewOutput>;

const messages = {
  INVALID_INPUT: "Проверьте параметры запроса.",
  UNAUTHENTICATED: "Требуется действующая сессия.",
  FORBIDDEN: "Для текущей роли или сессии действие недоступно.",
  CASE_NOT_FOUND: "Заявка недоступна или не найдена.",
  PREVIEW_NOT_READY: "Откройте «Примерку кредита» в карточке заявки и проверьте исходные данные.",
  SCENARIO_NOT_APPLICABLE: "Откройте актуальную карточку и проверьте доступные варианты.",
  DEPENDENCY_UNAVAILABLE: "Проверенные данные сейчас недоступны. Повторите позже.",
  FIXTURE_NOT_AVAILABLE: "Эта комбинация отсутствует в синтетическом наборе.",
} as const;

export class ToolError extends Error {
  constructor(readonly code: keyof typeof messages) {
    super(messages[code]);
  }
}

export interface Provider {
  list(args: ListArgs): Promise<ListResult>;
  summary(args: SummaryArgs): Promise<SummaryResult>;
  preview(args: PreviewArgs): Promise<PreviewResult>;
}
