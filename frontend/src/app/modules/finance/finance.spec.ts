import { CATEGORY_LABELS, EXPENSE_CATEGORIES, INCOME_CATEGORIES, splitInstallments } from './finance.models';
import { fmtMonth, periodRange } from './finance-page';

describe('financeiro (utilitários)', () => {
  it('divide parcelas como o backend (resto na 1ª)', () => {
    expect(splitInstallments(300001, 3)).toEqual([100001, 100000, 100000]);
    expect(splitInstallments(1000, 1)).toEqual([1000]);
    expect(splitInstallments(100, 3).reduce((a, b) => a + b, 0)).toBe(100);
  });

  it('categorias de receita e despesa são disjuntas e rotuladas', () => {
    const all = [...INCOME_CATEGORIES, ...EXPENSE_CATEGORIES];
    expect(new Set(all).size).toBe(all.length);
    expect(all.length).toBe(Object.keys(CATEGORY_LABELS).length);
  });

  it('períodos terminam no fim do mês corrente', () => {
    const ref = new Date(2026, 10, 7);
    expect(periodRange('mes', ref)).toEqual({ from: '2026-11-01', to: '2026-11-30' });
    expect(periodRange('6m', ref).from).toBe('2026-06-01');
    expect(periodRange('ano', ref).from).toBe('2026-01-01');
  });

  it('formata mês abreviado', () => {
    expect(fmtMonth('2026-11')).toBe('nov/26');
  });
});
