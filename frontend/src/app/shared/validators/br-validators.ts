import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

export const onlyDigits = (value: string | null | undefined): string => (value ?? '').replace(/\D/g, '');

export function isValidCpf(value: string): boolean {
  const cpf = onlyDigits(value);
  if (cpf.length !== 11 || /^(\d)\1+$/.test(cpf)) return false;
  const digit = (len: number) => {
    let sum = 0;
    for (let i = 0; i < len; i++) sum += Number(cpf[i]) * (len + 1 - i);
    const mod = (sum * 10) % 11;
    return mod === 10 ? 0 : mod;
  };
  return digit(9) === Number(cpf[9]) && digit(10) === Number(cpf[10]);
}

export function isValidCnpj(value: string): boolean {
  const cnpj = onlyDigits(value);
  if (cnpj.length !== 14 || /^(\d)\1+$/.test(cnpj)) return false;
  const calc = (weights: number[]) => {
    const sum = weights.reduce((acc, w, i) => acc + Number(cnpj[i]) * w, 0);
    const mod = sum % 11;
    return mod < 2 ? 0 : 11 - mod;
  };
  return (
    calc([5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]) === Number(cnpj[12]) &&
    calc([6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2]) === Number(cnpj[13])
  );
}

/** Dígito verificador do número único CNJ (módulo 97, ISO 7064). */
export function isValidCnj(value: string): boolean {
  const d = onlyDigits(value);
  if (d.length !== 20) return false;
  const base = d.slice(0, 7) + d.slice(9) + '00';
  let mod = 0;
  for (const c of base) mod = (mod * 10 + Number(c)) % 97;
  return 98 - mod === Number(d.slice(7, 9));
}

export function formatCpfCnpj(value: string): string {
  const d = onlyDigits(value);
  if (d.length <= 11) {
    return d
      .replace(/(\d{3})(\d)/, '$1.$2')
      .replace(/(\d{3})(\d)/, '$1.$2')
      .replace(/(\d{3})(\d{1,2})$/, '$1-$2');
  }
  return d
    .slice(0, 14)
    .replace(/(\d{2})(\d)/, '$1.$2')
    .replace(/(\d{3})(\d)/, '$1.$2')
    .replace(/(\d{3})(\d)/, '$1/$2')
    .replace(/(\d{4})(\d{1,2})$/, '$1-$2');
}

export function formatCnj(value: string): string {
  const d = onlyDigits(value).slice(0, 20);
  const parts = [d.slice(0, 7), d.slice(7, 9), d.slice(9, 13), d.slice(13, 14), d.slice(14, 16), d.slice(16, 20)];
  let out = parts[0];
  if (parts[1]) out += '-' + parts[1];
  if (parts[2]) out += '.' + parts[2];
  if (parts[3]) out += '.' + parts[3];
  if (parts[4]) out += '.' + parts[4];
  if (parts[5]) out += '.' + parts[5];
  return out;
}

export function formatPhone(value: string): string {
  const d = onlyDigits(value).slice(0, 11);
  if (d.length <= 10) return d.replace(/(\d{2})(\d)/, '($1) $2').replace(/(\d{4})(\d{1,4})$/, '$1-$2');
  return d.replace(/(\d{2})(\d)/, '($1) $2').replace(/(\d{5})(\d{1,4})$/, '$1-$2');
}

/** CPF (PF) ou CNPJ (PJ) válido. Vazio é aceito (combine com Validators.required). */
export function cpfCnpjValidator(kind?: () => 'PF' | 'PJ'): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value as string;
    if (!value) return null;
    const d = onlyDigits(value);
    const type = kind?.();
    if (type === 'PF') return isValidCpf(d) ? null : { cpf: true };
    if (type === 'PJ') return isValidCnpj(d) ? null : { cnpj: true };
    return isValidCpf(d) || isValidCnpj(d) ? null : { cpfCnpj: true };
  };
}

export const cnjValidator: ValidatorFn = (control) =>
  !control.value || isValidCnj(control.value as string) ? null : { cnj: true };

/** Converte "1.234,56" / "1234.56" em centavos. */
export function parseMoneyToCents(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined || value === '') return null;
  if (typeof value === 'number') return Math.round(value * 100);
  const normalized = value.replace(/[^\d,.-]/g, '').replace(/\.(?=\d{3}(\D|$))/g, '').replace(',', '.');
  const n = Number(normalized);
  return Number.isFinite(n) ? Math.round(n * 100) : null;
}

export function centsToInput(cents: number | null | undefined): string {
  if (cents === null || cents === undefined) return '';
  return (cents / 100).toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
