// Modelos do módulo financeiro (espelham backend/.../dto/FinanceDtos.java).

export type FinanceDirection = 'RECEITA' | 'DESPESA';
export type FinanceScope = 'ESCRITORIO' | 'PESSOAL';
export type TransactionStatus = 'ABERTO' | 'PAGO' | 'CANCELADO';
export type TransactionSituation = 'A_VENCER' | 'VENCE_HOJE' | 'ATRASADO' | 'PAGO' | 'CANCELADO';
export type TransactionView = 'ABERTOS' | 'A_VENCER' | 'ATRASADOS' | 'PAGOS' | 'CANCELADOS' | 'TODOS';
export type PaymentMethod = 'PIX' | 'BOLETO' | 'TRANSFERENCIA' | 'CARTAO' | 'DINHEIRO' | 'CHEQUE' | 'OUTRO';
export type FinanceCategory =
  | 'HONORARIOS' | 'HONORARIOS_EXITO' | 'CONSULTORIA' | 'CONTRATO' | 'SERVICO_JURIDICO' | 'REEMBOLSO' | 'OUTRA_RECEITA'
  | 'IMPOSTOS' | 'ALUGUEL' | 'ENERGIA' | 'INTERNET' | 'PESSOAL' | 'PRO_LABORE' | 'SISTEMAS' | 'MARKETING'
  | 'FORNECEDORES' | 'CUSTAS' | 'OPERACIONAL' | 'OUTRA_DESPESA';

export interface Transaction {
  id: string;
  direction: FinanceDirection;
  category: FinanceCategory;
  categoryLabel: string;
  scope: FinanceScope;
  description: string;
  amountCents: number;
  dueDate: string;
  status: TransactionStatus;
  situation: TransactionSituation;
  daysOverdue: number | null;
  paidDate: string | null;
  paidAmountCents: number | null;
  paymentMethod: PaymentMethod | null;
  clientId: string | null;
  clientName: string | null;
  caseId: string | null;
  caseNumber: string | null;
  counterparty: string | null;
  documentNumber: string | null;
  notes: string | null;
  groupId: string | null;
  installmentNumber: number | null;
  installmentCount: number | null;
  recurrent: boolean;
  canceledReason: string | null;
  hasReceipt: boolean;
  receiptName: string | null;
}

export interface TransactionRequest {
  direction: FinanceDirection;
  category: FinanceCategory;
  description: string;
  amountCents: number;
  dueDate: string;
  clientId?: string | null;
  caseId?: string | null;
  counterparty?: string | null;
  documentNumber?: string | null;
  paymentMethod?: PaymentMethod | null;
  notes?: string | null;
  scope?: FinanceScope;
  installments?: number;
  recurrenceMonths?: number;
}

export interface MonthFlow {
  month: string;
  incomeCents: number;
  expenseCents: number;
  netCents: number;
  cumulativeCents: number;
  forecastIncomeCents: number;
  forecastExpenseCents: number;
}

export interface CategoryAmount {
  category: FinanceCategory;
  label: string;
  group: string;
  amountCents: number;
}

export interface DreLine {
  label: string;
  amountCents: number;
  level: number;
  kind: 'total' | 'income' | 'expense' | 'result';
}

export interface FinanceSummary {
  from: string;
  to: string;
  scope: FinanceScope;
  receivedCents: number;
  paidCents: number;
  balanceCents: number;
  receivableCents: number;
  payableCents: number;
  overdueReceivableCents: number;
  overdueReceivableCount: number;
  overduePayableCents: number;
  overduePayableCount: number;
  feesReceivedCents: number;
  feesPendingCents: number;
  currentMonthIncomeCents: number;
  monthly: MonthFlow[];
  projection: MonthFlow[];
  incomeByCategory: CategoryAmount[];
  expenseByCategory: CategoryAmount[];
  dre: {
    lines: DreLine[];
    grossRevenueCents: number;
    deductionsCents: number;
    netRevenueCents: number;
    expensesCents: number;
    resultCents: number;
    marginPercent: number | null;
  };
}

export interface DelinquencyReport {
  totalCents: number;
  count: number;
  truncated: boolean;
  aging: { label: string; amountCents: number; count: number }[];
  clients: { clientId: string | null; clientName: string; totalCents: number; count: number; oldestDueDate: string; maxDaysOverdue: number }[];
}

export const CATEGORY_LABELS: Record<FinanceCategory, string> = {
  HONORARIOS: 'Honorários',
  HONORARIOS_EXITO: 'Honorários de êxito',
  CONSULTORIA: 'Consultorias',
  CONTRATO: 'Contratos (partido mensal)',
  SERVICO_JURIDICO: 'Serviços jurídicos avulsos',
  REEMBOLSO: 'Reembolso de despesas',
  OUTRA_RECEITA: 'Outras receitas',
  IMPOSTOS: 'Impostos sobre receita',
  ALUGUEL: 'Aluguel e condomínio',
  ENERGIA: 'Energia',
  INTERNET: 'Internet e telefonia',
  PESSOAL: 'Funcionários e encargos',
  PRO_LABORE: 'Pró-labore',
  SISTEMAS: 'Sistemas e tecnologia',
  MARKETING: 'Marketing',
  FORNECEDORES: 'Fornecedores',
  CUSTAS: 'Custas e despesas processuais',
  OPERACIONAL: 'Despesas operacionais',
  OUTRA_DESPESA: 'Outras despesas',
};

export const INCOME_CATEGORIES: FinanceCategory[] = [
  'HONORARIOS', 'HONORARIOS_EXITO', 'CONSULTORIA', 'CONTRATO', 'SERVICO_JURIDICO', 'REEMBOLSO', 'OUTRA_RECEITA',
];
export const EXPENSE_CATEGORIES: FinanceCategory[] = [
  'IMPOSTOS', 'ALUGUEL', 'ENERGIA', 'INTERNET', 'PESSOAL', 'PRO_LABORE', 'SISTEMAS', 'MARKETING', 'FORNECEDORES',
  'CUSTAS', 'OPERACIONAL', 'OUTRA_DESPESA',
];

export const PAYMENT_LABELS: Record<PaymentMethod, string> = {
  PIX: 'Pix',
  BOLETO: 'Boleto',
  TRANSFERENCIA: 'Transferência',
  CARTAO: 'Cartão',
  DINHEIRO: 'Dinheiro',
  CHEQUE: 'Cheque',
  OUTRO: 'Outro',
};

export const TX_SITUATION_LABELS: Record<TransactionSituation, string> = {
  A_VENCER: 'A vencer',
  VENCE_HOJE: 'Vence hoje',
  ATRASADO: 'Atrasado',
  PAGO: 'Pago',
  CANCELADO: 'Cancelado',
};

export const TX_SITUATION_TONE: Record<TransactionSituation, string> = {
  A_VENCER: 'info',
  VENCE_HOJE: 'warning',
  ATRASADO: 'danger',
  PAGO: 'success',
  CANCELADO: '',
};

/** Divide o valor em parcelas como o backend: o resto (centavos) vai para a primeira. */
export function splitInstallments(totalCents: number, n: number): number[] {
  if (n <= 1) return [totalCents];
  const base = Math.floor(totalCents / n);
  const rest = totalCents - base * n;
  return Array.from({ length: n }, (_, i) => (i === 0 ? base + rest : base));
}
