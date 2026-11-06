import { FormControl } from '@angular/forms';
import {
  cnjValidator,
  cpfCnpjValidator,
  formatCnj,
  formatCpfCnpj,
  formatPhone,
  isValidCnj,
  isValidCnpj,
  isValidCpf,
  parseMoneyToCents,
} from './br-validators';

describe('validadores brasileiros', () => {
  it('valida CPF', () => {
    expect(isValidCpf('529.982.247-25')).toBeTrue();
    expect(isValidCpf('529.982.247-24')).toBeFalse();
    expect(isValidCpf('111.111.111-11')).toBeFalse();
  });

  it('valida CNPJ', () => {
    expect(isValidCnpj('11.222.333/0001-81')).toBeTrue();
    expect(isValidCnpj('11.222.333/0001-80')).toBeFalse();
  });

  it('valida número CNJ (módulo 97)', () => {
    expect(isValidCnj('1234567-13.2024.8.26.0100')).toBeTrue();
    expect(isValidCnj('1234567-14.2024.8.26.0100')).toBeFalse();
  });

  it('aplica máscaras', () => {
    expect(formatCpfCnpj('52998224725')).toBe('529.982.247-25');
    expect(formatCpfCnpj('11222333000181')).toBe('11.222.333/0001-81');
    expect(formatCnj('12345671320248260100')).toBe('1234567-13.2024.8.26.0100');
    expect(formatPhone('11988887777')).toBe('(11) 98888-7777');
  });

  it('validator de CPF/CNPJ respeita o tipo de pessoa', () => {
    const pf = new FormControl('11.222.333/0001-81', cpfCnpjValidator(() => 'PF'));
    expect(pf.hasError('cpf')).toBeTrue();
    const pj = new FormControl('11.222.333/0001-81', cpfCnpjValidator(() => 'PJ'));
    expect(pj.valid).toBeTrue();
    expect(new FormControl('', cpfCnpjValidator()).valid).toBeTrue();
  });

  it('validator CNJ aceita vazio', () => {
    expect(new FormControl('', cnjValidator).valid).toBeTrue();
    expect(new FormControl('123', cnjValidator).hasError('cnj')).toBeTrue();
  });

  it('converte valores monetários para centavos', () => {
    expect(parseMoneyToCents('48.500,00')).toBe(4850000);
    expect(parseMoneyToCents('1234,5')).toBe(123450);
    expect(parseMoneyToCents('')).toBeNull();
  });
});
