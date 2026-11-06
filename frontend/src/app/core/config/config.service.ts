import { Injectable } from '@angular/core';
import type { FirebaseOptions } from 'firebase/app';

/**
 * Configuração carregada em tempo de execução de /config.json.
 * Em Docker, o Nginx gera esse arquivo a partir de variáveis de ambiente — a mesma imagem serve
 * qualquer ambiente. Contém apenas a configuração PÚBLICA do Firebase Web (nenhuma credencial
 * administrativa).
 */
export interface AppConfig {
  apiUrl: string;
  firebase: FirebaseOptions;
  emulators?: {
    authUrl?: string;
    firestoreHost?: string;
    firestorePort?: number;
  };
}

@Injectable({ providedIn: 'root' })
export class ConfigService {
  private config: AppConfig | null = null;

  async load(): Promise<void> {
    const response = await fetch('/config.json', { cache: 'no-store' });
    if (!response.ok) {
      throw new Error('Não foi possível carregar /config.json');
    }
    this.config = (await response.json()) as AppConfig;
  }

  /** Para testes. */
  set(config: AppConfig): void {
    this.config = config;
  }

  get value(): AppConfig {
    if (!this.config) {
      throw new Error('Configuração ainda não carregada');
    }
    return this.config;
  }

  get apiUrl(): string {
    return this.value.apiUrl.replace(/\/$/, '');
  }
}
