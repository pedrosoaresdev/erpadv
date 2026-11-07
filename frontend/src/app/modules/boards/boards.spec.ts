import { generatePassword, isStrongPassword, passwordMatchValidator } from '../../shared/validators/password';
import { FormControl, FormGroup } from '@angular/forms';
import { daysSince, dueState, positionBetween } from './boards.service';
import { STAGES } from '../crm/crm.service';

describe('utilitários dos quadros', () => {
  it('posição fracionária entre vizinhos', () => {
    expect(positionBetween(null, null)).toBe(1024);
    expect(positionBetween(null, 1024)).toBe(512);
    expect(positionBetween(2048, null)).toBe(3072);
    expect(positionBetween(1024, 2048)).toBe(1536);
  });

  it('situação do vencimento', () => {
    const today = new Date(2026, 10, 9);
    expect(dueState(null, false, today)).toBe('none');
    expect(dueState('2026-11-08', false, today)).toBe('overdue');
    expect(dueState('2026-11-08', true, today)).toBe('done');
    expect(dueState('2026-11-09', false, today)).toBe('today');
    expect(dueState('2026-11-12', false, today)).toBe('soon');
    expect(dueState('2026-11-20', false, today)).toBe('later');
  });

  it('dias desde a entrada na coluna', () => {
    const now = new Date('2026-11-09T12:00:00Z').getTime();
    expect(daysSince(null, now)).toBe(0);
    expect(daysSince('2026-11-01T12:00:00Z', now)).toBe(8);
    expect(daysSince('2026-11-10T12:00:00Z', now)).toBe(0);
  });
});

describe('senhas definidas pelo administrador', () => {
  it('segue a política do backend', () => {
    expect(isStrongPassword('Senha-Forte-123')).toBeTrue();
    expect(isStrongPassword('curta1')).toBeFalse();
    expect(isStrongPassword('somenteletras')).toBeFalse();
    expect(isStrongPassword('1234567890')).toBeFalse();
  });

  it('gera senhas fortes e diferentes', () => {
    const a = generatePassword();
    const b = generatePassword();
    expect(a.length).toBe(14);
    expect(isStrongPassword(a)).toBeTrue();
    expect(a).not.toBe(b);
  });

  it('confirmação precisa conferir', () => {
    const group = new FormGroup({ password: new FormControl('Senha-Forte-123'), confirm: new FormControl('outra') });
    expect(passwordMatchValidator(group)).toEqual({ passwordMismatch: true });
    group.controls.confirm.setValue('Senha-Forte-123');
    expect(passwordMatchValidator(group)).toBeNull();
  });
});

describe('funil do CRM', () => {
  it('tem as etapas abertas antes das finais', () => {
    expect(STAGES.map((s) => s.key)).toEqual(['NOVO', 'CONTATO', 'QUALIFICADO', 'PROPOSTA', 'NEGOCIACAO', 'GANHO', 'PERDIDO']);
    expect(STAGES.filter((s) => !s.open).map((s) => s.key)).toEqual(['GANHO', 'PERDIDO']);
  });
});
