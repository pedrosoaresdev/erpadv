import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/** Mesma política do backend: 8+ caracteres com letras e números. */
export function isStrongPassword(value: string | null | undefined): boolean {
  const v = value ?? '';
  return v.length >= 8 && v.length <= 128 && /\p{L}/u.test(v) && /\d/.test(v);
}

export const passwordValidator: ValidatorFn = (control: AbstractControl): ValidationErrors | null =>
  !control.value || isStrongPassword(control.value) ? null : { weakPassword: true };

/** Confirmação: compara os controles "password" e "confirm" do grupo. */
export const passwordMatchValidator: ValidatorFn = (group: AbstractControl): ValidationErrors | null => {
  const password = group.get('password')?.value;
  const confirm = group.get('confirm')?.value;
  return password && confirm && password !== confirm ? { passwordMismatch: true } : null;
};

/** Senha provisória forte gerada no navegador com crypto.getRandomValues (nada é enviado a terceiros). */
export function generatePassword(length = 14): string {
  const letters = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz';
  const digits = '23456789';
  const symbols = '@#$%&*-+=?';
  const all = letters + digits + symbols;
  const random = new Uint32Array(length);
  crypto.getRandomValues(random);
  const chars = Array.from(random, (n) => all[n % all.length]);
  chars[0] = letters[random[0] % letters.length];
  chars[1] = digits[random[1] % digits.length];
  chars[2] = symbols[random[2] % symbols.length];
  // Embaralha (Fisher–Yates) para não deixar o padrão nas primeiras posições.
  const shuffle = new Uint32Array(length);
  crypto.getRandomValues(shuffle);
  for (let i = chars.length - 1; i > 0; i--) {
    const j = shuffle[i] % (i + 1);
    [chars[i], chars[j]] = [chars[j], chars[i]];
  }
  return chars.join('');
}
