import { BrDatePipe, CentsPipe, RelativeTimePipe } from './format.pipes';

describe('pipes de formatação', () => {
  it('formata centavos em reais', () => {
    const value = new CentsPipe().transform(4850000);
    expect(value.replace(/\s/g, ' ')).toBe('R$ 48.500,00');
    expect(new CentsPipe().transform(null)).toBe('—');
  });

  it('formata datas ISO sem deslocamento de fuso', () => {
    expect(new BrDatePipe().transform('2024-05-10')).toBe('10/05/2024');
    expect(new BrDatePipe().transform(null)).toBe('—');
  });

  it('tempo relativo', () => {
    expect(new RelativeTimePipe().transform(Date.now())).toBe('agora');
    expect(new RelativeTimePipe().transform(Date.now() - 5 * 60000)).toBe('há 5 min');
  });
});
