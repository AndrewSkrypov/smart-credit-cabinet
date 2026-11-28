import { applicationId, ToolError } from "./contract.ts";
import type { CaseItem, ListArgs, PreviewArgs, PreviewResult, Provider, SummaryArgs, SummaryResult } from "./contract.ts";

const metadata = { source: "synthetic-fixture", asOf: "2026-11-28T09:00:00Z" } as const;
export const fixtureIds = {
  vector: "40188819-ffc2-4caa-a57f-a8f9414391c1",
  north: "6b7a0f51-2958-4228-bb7b-68f23d57b161",
  other: "4dafdfe0-5555-4925-a1ae-e5ed62895036",
  expired: "7a3d9c07-3d90-47b7-a579-e5febf461765",
  excludedCompany: "3ccc19bc-8fe8-4d70-ab2b-bbcce8211237",
} as const;

export const profiles = ["agent", "empty", "client", "expired-session", "expired-mandate", "summary-only", "missing-basis"] as const;
type Profile = typeof profiles[number];
type Operation = keyof Provider;
type Mandate = "active" | "expired" | "revoked";
type RecordFixture = Omit<CaseItem, "cabinetPath"> & {
  assignedTo: string;
  companyAdmitted?: false;
  applicationVersion: SummaryResult["applicationVersion"];
  savedScenarioRevision: SummaryResult["savedScenarioRevision"];
  statementRequirement: SummaryResult["statementRequirement"];
};

function cabinetPath(id: string, anchor?: "draft-financing" | "application-info-request") {
  return "/agent/applications/" + applicationId.parse(id) + (anchor ? "#" + anchor : "");
}

const records: readonly RecordFixture[] = [
  {
    applicationId: fixtureIds.other, assignedTo: "other-agent", companyName: "Синтетическая компания A",
    number: "SYN-000", status: "DRAFT", actionOwner: "AGENT", actionLabel: "Проверить черновик",
    actionDeadline: null, applicationVersion: "1", savedScenarioRevision: null, statementRequirement: null,
  },
  {
    applicationId: fixtureIds.expired, assignedTo: "demo-agent", companyName: "Синтетическая компания B",
    number: "SYN-003", status: "DRAFT", actionOwner: "AGENT", actionLabel: "Проверить черновик",
    actionDeadline: null, applicationVersion: "1", savedScenarioRevision: null, statementRequirement: null,
  },
  {
    applicationId: fixtureIds.excludedCompany, assignedTo: "demo-agent", companyAdmitted: false,
    companyName: "Синтетическая компания C", number: "SYN-004", status: "DRAFT", actionOwner: "AGENT",
    actionLabel: "Проверить черновик", actionDeadline: null, applicationVersion: "1",
    savedScenarioRevision: null, statementRequirement: null,
  },
  {
    applicationId: fixtureIds.vector, assignedTo: "demo-agent", companyName: "Вектор Логистик",
    number: "SYN-001", status: "DRAFT", actionOwner: "AGENT", actionLabel: "Проверить сохранённую примерку",
    actionDeadline: "2026-12-15T09:00:00Z", applicationVersion: "4", savedScenarioRevision: "3",
    statementRequirement: "NOT_REQUESTED",
  },
  {
    applicationId: fixtureIds.north, assignedTo: "demo-agent", companyName: "Северная мебель",
    number: "SYN-002", status: "NEEDS_INFO", actionOwner: "CLIENT", actionLabel: "Предоставить запрошенную выписку",
    actionDeadline: null, applicationVersion: "5", savedScenarioRevision: null, statementRequirement: "OPEN",
  },
];

function project(record: RecordFixture): CaseItem {
  const { applicationId, number, companyName, status, actionOwner, actionLabel, actionDeadline } = record;
  return {
    applicationId, number, companyName, status, actionOwner, actionLabel, actionDeadline,
    cabinetPath: cabinetPath(applicationId, record.statementRequirement === "OPEN" ? "application-info-request" : undefined),
  };
}

function previewFixture(delayDays: number): PreviewResult {
  const included = delayDays === 5;
  return {
    ...metadata, preview: true,
    basis: { applicationVersion: "4", productCode: "working-capital", productVersion: "2", savedScenarioRevision: "3" },
    inputsUsed: {
      currency: "RUB", paymentModel: "ANNUITY", loanAmountRub: "2000000", annualRatePercent: "20",
      baseTermMonths: 12, alternativeTermMonths: 24, openingBalanceRub: "150000",
      expectedReceiptAmountRub: "140000", expectedReceiptDate: "2026-12-10", paymentDate: "2026-12-15",
      delayDays, receiptIncludedByPayment: included, lastIncludedDelayDays: 5,
    },
    options: [
      { termMonths: 12, monthlyPaymentRub: "185269.01", totalInterestRub: "223228.15",
        balanceOnPaymentDateRub: included ? "104730.99" : "-35269.01" },
      { termMonths: 24, monthlyPaymentRub: "101791.61", totalInterestRub: "442998.50",
        balanceOnPaymentDateRub: included ? "188208.39" : "48208.39" },
    ],
    cabinetPath: cabinetPath(fixtureIds.vector, "draft-financing"),
  };
}

// Mutable state belongs to the fixture harness. The provider returned to MCP has only read operations.
export function createFixtures(profile?: Profile) {
  let available = true;
  const mandates = new Map<string, Mandate>([
    [fixtureIds.vector, profile === "expired-mandate" ? "expired" : "active"],
    [fixtureIds.north, "active"], [fixtureIds.other, "active"], [fixtureIds.expired, "expired"],
    [fixtureIds.excludedCompany, "active"],
  ]);

  function authorize(operation: Operation) {
    if (!available) throw new ToolError("DEPENDENCY_UNAVAILABLE");
    if (!profile || profile === "expired-session") throw new ToolError("UNAUTHENTICATED");
    if (profile === "client" || (profile === "summary-only" && operation !== "summary")) {
      throw new ToolError("FORBIDDEN");
    }
    return profile === "empty" ? "empty-agent" : "demo-agent";
  }

  function canSee(record: RecordFixture, agent: string) {
    return record.assignedTo === agent && record.companyAdmitted !== false
      && mandates.get(record.applicationId) === "active";
  }

  function get(id: string, operation: Operation) {
    const agent = authorize(operation);
    const record = records.find(item => item.applicationId === id.toLowerCase() && canSee(item, agent));
    if (!record) throw new ToolError("CASE_NOT_FOUND");
    return profile === "missing-basis" && record.applicationId === fixtureIds.vector
      ? { ...record, savedScenarioRevision: null }
      : record;
  }

  const provider = Object.freeze({
    async list({ limit }: ListArgs) {
      const agent = authorize("list");
      return { ...metadata, items: records.filter(record => canSee(record, agent) && record.actionLabel)
        .slice(0, limit).map(project) };
    },
    async summary({ applicationId: id }: SummaryArgs) {
      const record = get(id, "summary");
      const { applicationVersion, savedScenarioRevision, statementRequirement } = record;
      return { ...metadata, application: project(record), applicationVersion, savedScenarioRevision, statementRequirement };
    },
    async preview({ applicationId: id, alternativeTermMonths, delayDays }: PreviewArgs) {
      const record = get(id, "preview");
      if (record.status !== "DRAFT") throw new ToolError("SCENARIO_NOT_APPLICABLE");
      if (!record.savedScenarioRevision) throw new ToolError("PREVIEW_NOT_READY");
      if (![24, 36].includes(alternativeTermMonths)) throw new ToolError("INVALID_INPUT");
      if (alternativeTermMonths !== 24 || (delayDays !== 5 && delayDays !== 6)) {
        throw new ToolError("FIXTURE_NOT_AVAILABLE");
      }
      return previewFixture(delayDays);
    },
  } satisfies Provider);
  return {
    provider,
    revoke: () => mandates.set(fixtureIds.vector, "revoked"),
    makeUnavailable: () => { available = false; },
  };
}
