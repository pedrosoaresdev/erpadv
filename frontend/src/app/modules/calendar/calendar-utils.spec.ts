import { countdownLabel } from '../deadlines/deadlines-list';
import { HOUR_PX, fromLocal, monthGrid, occursOn, rangeFor, shift, startOfWeek, timeBox, weekDays } from './calendar-utils';

describe('utilitários da agenda', () => {
  it('grade do mês tem 6 semanas começando no domingo', () => {
    const grid = monthGrid(new Date(2026, 10, 15)); // novembro/2026 começa num domingo
    expect(grid.length).toBe(42);
    expect(grid[0].getDay()).toBe(0);
    expect(grid[0].getDate()).toBe(1);
    expect(grid[41].getMonth()).toBe(11);
  });

  it('semana começa no domingo', () => {
    const s = startOfWeek(new Date(2026, 10, 12)); // quinta
    expect(s.getDate()).toBe(8);
    expect(weekDays(new Date(2026, 10, 12)).map((d) => d.getDate())).toEqual([8, 9, 10, 11, 12, 13, 14]);
  });

  it('intervalos por visão', () => {
    const ref = new Date(2026, 10, 12);
    const day = rangeFor('day', ref);
    expect(day.to.getTime() - day.from.getTime()).toBe(86_400_000);
    const week = rangeFor('week', ref);
    expect(week.from.getDate()).toBe(8);
    expect(shift('month', ref, 1).getMonth()).toBe(11);
    expect(shift('week', ref, -1).getDate()).toBe(5);
  });

  it('eventos que atravessam o dia aparecem nele', () => {
    const ev = { start: fromLocal('2026-11-12', '23:00').toISOString(), end: fromLocal('2026-11-13', '01:00').toISOString() };
    expect(occursOn([ev], new Date(2026, 10, 12)).length).toBe(1);
    expect(occursOn([ev], new Date(2026, 10, 13)).length).toBe(1);
    expect(occursOn([ev], new Date(2026, 10, 14)).length).toBe(0);
  });

  it('posição do evento na grade horária', () => {
    const box = timeBox(fromLocal('2026-11-12', '09:30').toISOString(), fromLocal('2026-11-12', '11:00').toISOString(), new Date(2026, 10, 12));
    expect(box.top).toBe(9.5 * HOUR_PX);
    expect(box.height).toBe(1.5 * HOUR_PX);
  });

  it('rótulo de contagem regressiva do prazo', () => {
    expect(countdownLabel({ situation: 'HOJE', daysLeft: 0 })).toBe('vence hoje');
    expect(countdownLabel({ situation: 'URGENTE', daysLeft: 1 })).toBe('vence amanhã');
    expect(countdownLabel({ situation: 'FUTURO', daysLeft: 12 })).toBe('em 12 dias');
    expect(countdownLabel({ situation: 'VENCIDO', daysLeft: -3 })).toBe('vencido há 3 dias');
    expect(countdownLabel({ situation: 'CONCLUIDO', daysLeft: -3 })).toBe('');
  });
});
