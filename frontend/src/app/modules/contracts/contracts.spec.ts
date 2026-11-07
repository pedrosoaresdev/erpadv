import { formatBytes } from '../documents/documents.service';
import { Contract } from './contracts.service';
import { feeSummary } from './contracts-list';

const base = { stages: [], successPercentBp: null } as unknown as Contract;

describe('contratos e documentos (utilitários)', () => {
  it('resume honorários por modalidade', () => {
    expect(feeSummary({ ...base, feeModel: 'PARCELADO', amountCents: 900000, installments: 3 } as Contract).replace(/\s/g, ' ')).toBe('R$ 9.000,00 em 3x');
    expect(feeSummary({ ...base, feeModel: 'FIXO', amountCents: 150000, successPercentBp: 2000 } as Contract).replace(/\s/g, ' ')).toBe('R$ 1.500,00 + 20% êxito');
    expect(feeSummary({ ...base, feeModel: 'EXITO', successPercentBp: 3000 } as Contract)).toBe('30% de êxito');
    expect(feeSummary({ ...base, feeModel: 'ETAPAS', stages: [{ amountCents: 100000 }, { amountCents: 50000 }] } as unknown as Contract).replace(/\s/g, ' ')).toBe('2 etapa(s) · R$ 1.500,00');
  });

  it('formata tamanho de arquivo', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(2048)).toBe('2 KB');
    expect(formatBytes(3.5 * 1024 * 1024)).toBe('3,5 MB');
  });
});
