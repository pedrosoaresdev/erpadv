import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import {
  User,
  createUserWithEmailAndPassword,
  onAuthStateChanged,
  sendEmailVerification,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signOut,
  updateProfile,
} from 'firebase/auth';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../api/api.service';
import { FirebaseService } from '../config/firebase.service';
import { Me, Permission, SessionStatus } from '../models/models';

/**
 * Estado de autenticação. O Firebase Auth cuida da identidade (senha, e-mail, tokens);
 * papel, escritório e permissões vêm do backend (/api/auth/me), que é a autoridade.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly firebase = inject(FirebaseService);
  private readonly api = inject(ApiService);
  private readonly router = inject(Router);

  readonly user = signal<User | null>(null);
  readonly me = signal<Me | null>(null);
  /** Situação da sessão no servidor (2FA, inatividade, justificativa); só para integrantes de um escritório. */
  readonly security = signal<SessionStatus | null>(null);
  readonly initialized = signal(false);

  readonly permissions = computed(() => new Set(this.me()?.permissions ?? []));
  readonly displayName = computed(() => this.me()?.name || this.user()?.displayName || this.user()?.email || '');
  readonly initials = computed(() =>
    this.displayName()
      .split(/\s+/)
      .filter((p) => p.length > 2 || /^[A-ZÀ-Ú]/.test(p))
      .slice(0, 2)
      .map((p) => p[0]?.toUpperCase())
      .join('') || '?',
  );

  private readyPromise: Promise<void> | null = null;

  /** Resolve quando o estado inicial (usuário + perfil) estiver carregado. */
  ready(): Promise<void> {
    if (!this.readyPromise) {
      this.readyPromise = new Promise<void>((resolve) => {
        let first = true;
        onAuthStateChanged(this.firebase.auth, async (user) => {
          this.user.set(user);
          if (user) {
            await this.loadMe().catch(() => this.me.set(null));
          } else {
            this.me.set(null);
          }
          if (first) {
            first = false;
            this.initialized.set(true);
            resolve();
          }
        });
      });
    }
    return this.readyPromise;
  }

  has(permission: Permission): boolean {
    return this.permissions().has(permission);
  }

  hasAny(permissions: Permission[]): boolean {
    return permissions.some((p) => this.has(p));
  }

  async token(): Promise<string | null> {
    const current = this.firebase.auth.currentUser;
    return current ? current.getIdToken() : null;
  }

  async loadMe(): Promise<Me> {
    const me = await firstValueFrom(this.api.get<Me>('/auth/me', undefined, true));
    if (me.officeId && !me.onboardingRequired && me.role !== 'CLIENTE' && me.role !== 'MASTER') {
      await this.loadSecurity();
    } else {
      this.security.set(null);
    }
    this.me.set(me);
    return me;
  }

  async loadSecurity(): Promise<SessionStatus | null> {
    const status = await firstValueFrom(this.api.get<SessionStatus>('/security/session', undefined, true)).catch(() => null);
    this.security.set(status);
    return status;
  }

  /** Segundo fator pendente nesta sessão (configuração exigida ou código a confirmar). */
  mfaPending(): boolean {
    const s = this.security();
    return !!s && (s.enrollmentRequired || (s.mfaRequired && !s.mfaVerified));
  }

  async login(email: string, password: string): Promise<Me> {
    const credential = await signInWithEmailAndPassword(this.firebase.auth, email.trim(), password);
    this.user.set(credential.user);
    const me = await this.loadMe();
    if (!me.onboardingRequired) {
      await firstValueFrom(this.api.post<void>('/auth/session', {}, true)).catch(() => undefined);
    }
    return me;
  }

  async register(name: string, email: string, password: string): Promise<void> {
    const credential = await createUserWithEmailAndPassword(this.firebase.auth, email.trim(), password);
    await updateProfile(credential.user, { displayName: name.trim() });
    await sendEmailVerification(credential.user);
    this.user.set(credential.user);
    await this.loadMe();
  }

  async resendVerification(): Promise<void> {
    const current = this.firebase.auth.currentUser;
    if (current) {
      await sendEmailVerification(current);
    }
  }

  /** Recarrega o usuário e força novo token (após verificar e-mail ou mudança de claims). */
  async refresh(): Promise<Me> {
    const current = this.firebase.auth.currentUser;
    if (current) {
      await current.reload();
      await current.getIdToken(true);
      this.user.set(this.firebase.auth.currentUser);
    }
    return this.loadMe();
  }

  resetPassword(email: string): Promise<void> {
    return sendPasswordResetEmail(this.firebase.auth, email.trim());
  }

  async logout(redirect = true): Promise<void> {
    await signOut(this.firebase.auth);
    this.me.set(null);
    this.security.set(null);
    this.user.set(null);
    if (redirect) {
      await this.router.navigate(['/login']);
    }
  }

  /** Caminho inicial conforme a situação do usuário. */
  homePath(me: Me | null = this.me()): string {
    if (!me) return '/login';
    if (me.emailVerificationRequired) return '/verificar-email';
    if (me.role === 'MASTER' && !me.officeId) return '/plataforma';
    if (me.onboardingRequired) return '/primeiro-acesso';
    if (this.mfaPending()) return '/verificacao-2fa';
    if (me.role === 'CLIENTE') return '/sem-acesso';
    return '/dashboard';
  }
}

/** Mensagens amigáveis para os códigos do Firebase Auth. */
export function firebaseAuthMessage(error: unknown): string {
  const code = (error as { code?: string })?.code ?? '';
  const messages: Record<string, string> = {
    'auth/invalid-credential': 'E-mail ou senha incorretos.',
    'auth/wrong-password': 'E-mail ou senha incorretos.',
    'auth/user-not-found': 'E-mail ou senha incorretos.',
    'auth/user-disabled': 'Conta bloqueada. Procure o administrador do escritório.',
    'auth/too-many-requests': 'Muitas tentativas. Aguarde alguns minutos e tente novamente.',
    'auth/email-already-in-use': 'Este e-mail já possui cadastro.',
    'auth/weak-password': 'Senha fraca. Use ao menos 8 caracteres com letras e números.',
    'auth/invalid-email': 'E-mail inválido.',
    'auth/network-request-failed': 'Falha de conexão. Verifique sua internet.',
  };
  return messages[code] ?? 'Não foi possível concluir a operação. Tente novamente.';
}
