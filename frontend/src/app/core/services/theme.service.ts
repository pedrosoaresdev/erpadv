import { Injectable, effect, signal } from '@angular/core';

export type ThemeMode = 'light' | 'dark';
const STORAGE_KEY = 'erpadv.theme';

/** Tema claro/escuro (preferência do usuário guardada localmente). */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  readonly mode = signal<ThemeMode>(this.initial());

  constructor() {
    effect(() => {
      const mode = this.mode();
      document.documentElement.classList.toggle('dark', mode === 'dark');
      try {
        localStorage.setItem(STORAGE_KEY, mode);
      } catch {
        /* armazenamento indisponível: apenas não persiste */
      }
    });
  }

  toggle(): void {
    this.mode.update((m) => (m === 'dark' ? 'light' : 'dark'));
  }

  private initial(): ThemeMode {
    try {
      const saved = localStorage.getItem(STORAGE_KEY);
      if (saved === 'light' || saved === 'dark') return saved;
    } catch {
      /* ignora */
    }
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
}
