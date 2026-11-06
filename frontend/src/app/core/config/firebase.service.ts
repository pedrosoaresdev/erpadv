import { Injectable, inject } from '@angular/core';
import { FirebaseApp, initializeApp } from 'firebase/app';
import { Auth, browserLocalPersistence, connectAuthEmulator, getAuth, setPersistence } from 'firebase/auth';
import { Firestore, connectFirestoreEmulator, getFirestore } from 'firebase/firestore';
import { ConfigService } from './config.service';

/** Inicializa o SDK web do Firebase (identidade + listener de notificações). */
@Injectable({ providedIn: 'root' })
export class FirebaseService {
  private readonly config = inject(ConfigService);
  private app!: FirebaseApp;
  auth!: Auth;
  firestore!: Firestore;

  async init(): Promise<void> {
    const cfg = this.config.value;
    this.app = initializeApp(cfg.firebase);
    this.auth = getAuth(this.app);
    this.auth.languageCode = 'pt-BR';
    this.firestore = getFirestore(this.app);
    if (cfg.emulators?.authUrl) {
      connectAuthEmulator(this.auth, cfg.emulators.authUrl, { disableWarnings: true });
    }
    if (cfg.emulators?.firestoreHost) {
      connectFirestoreEmulator(this.firestore, cfg.emulators.firestoreHost, cfg.emulators.firestorePort ?? 8080);
    }
    await setPersistence(this.auth, browserLocalPersistence);
  }
}
