// Testes das Security Rules do Firestore e do Storage.
// Execução: npm run test:rules  (sobe os emuladores, roda os testes e encerra)
import { after, before, beforeEach, describe, it } from 'node:test';
import { readFileSync } from 'node:fs';
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, collection, getDocs, query, where, deleteDoc } from 'firebase/firestore';
import { ref, uploadString, getBytes } from 'firebase/storage';

const PROJECT_ID = 'demo-erpadv';
let env;

const advogadoA = { officeId: 'officeA', role: 'ADVOGADO' };
const advogadoB = { officeId: 'officeB', role: 'ADVOGADO' };
const clienteA = { officeId: 'officeA', role: 'CLIENTE', clientId: 'client1' };

before(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync('firestore.rules', 'utf8') },
    storage: { rules: readFileSync('storage.rules', 'utf8') },
  });
});

after(async () => {
  await env?.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  // Dados semeados como o backend faria (Admin SDK ignora as regras).
  await env.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await setDoc(doc(db, 'users/uidA'), { officeId: 'officeA', role: 'ADVOGADO' });
    await setDoc(doc(db, 'users/uidB'), { officeId: 'officeB', role: 'ADVOGADO' });
    await setDoc(doc(db, 'offices/officeA'), { name: 'Escritório A' });
    await setDoc(doc(db, 'offices/officeB'), { name: 'Escritório B' });
    await setDoc(doc(db, 'offices/officeA/clients/client1'), { officeId: 'officeA', name: 'Cliente 1' });
    await setDoc(doc(db, 'offices/officeA/audit_logs/1'), { officeId: 'officeA', seq: 1 });
    await setDoc(doc(db, 'offices/officeA/notifications/n1'), {
      officeId: 'officeA', recipientUid: 'uidA', title: 'Prazo', read: false,
    });
    await setDoc(doc(db, 'offices/officeA/notifications/n2'), {
      officeId: 'officeA', recipientUid: 'outro', title: 'Prazo', read: false,
    });
  });
});

describe('Firestore — perfil de usuário', () => {
  it('não autenticado não lê perfis', async () => {
    await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(), 'users/uidA')));
  });
  it('usuário lê o próprio perfil', async () => {
    await assertSucceeds(getDoc(doc(env.authenticatedContext('uidA', advogadoA).firestore(), 'users/uidA')));
  });
  it('usuário não lê perfil de outro', async () => {
    await assertFails(getDoc(doc(env.authenticatedContext('uidA', advogadoA).firestore(), 'users/uidB')));
  });
  it('usuário não altera o próprio papel', async () => {
    const db = env.authenticatedContext('uidA', advogadoA).firestore();
    await assertFails(updateDoc(doc(db, 'users/uidA'), { role: 'SOCIO_ADMIN' }));
  });
});

describe('Firestore — isolamento entre escritórios', () => {
  it('equipe lê o documento do próprio escritório', async () => {
    await assertSucceeds(getDoc(doc(env.authenticatedContext('uidA', advogadoA).firestore(), 'offices/officeA')));
  });
  it('equipe não lê escritório alheio', async () => {
    await assertFails(getDoc(doc(env.authenticatedContext('uidB', advogadoB).firestore(), 'offices/officeA')));
  });
  it('cliente do portal não lê o documento do escritório', async () => {
    await assertFails(getDoc(doc(env.authenticatedContext('c1', clienteA).firestore(), 'offices/officeA')));
  });
  it('ninguém lê clientes diretamente pelo SDK (somente via backend)', async () => {
    await assertFails(getDoc(doc(env.authenticatedContext('uidA', advogadoA).firestore(), 'offices/officeA/clients/client1')));
    await assertFails(getDoc(doc(env.authenticatedContext('uidB', advogadoB).firestore(), 'offices/officeA/clients/client1')));
    await assertFails(getDoc(doc(env.authenticatedContext('c1', clienteA).firestore(), 'offices/officeA/clients/client1')));
  });
  it('ninguém grava clientes diretamente', async () => {
    const db = env.authenticatedContext('uidA', { officeId: 'officeA', role: 'SOCIO_ADMIN' }).firestore();
    await assertFails(setDoc(doc(db, 'offices/officeA/clients/novo'), { name: 'X' }));
  });
});

describe('Firestore — auditoria imutável', () => {
  it('nem sócio administrador lê, altera ou apaga logs pelo SDK', async () => {
    const db = env.authenticatedContext('uidA', { officeId: 'officeA', role: 'SOCIO_ADMIN' }).firestore();
    await assertFails(getDoc(doc(db, 'offices/officeA/audit_logs/1')));
    await assertFails(updateDoc(doc(db, 'offices/officeA/audit_logs/1'), { seq: 2 }));
    await assertFails(deleteDoc(doc(db, 'offices/officeA/audit_logs/1')));
  });
});

describe('Firestore — notificações', () => {
  it('destinatário lê e lista as próprias notificações', async () => {
    const db = env.authenticatedContext('uidA', advogadoA).firestore();
    await assertSucceeds(getDoc(doc(db, 'offices/officeA/notifications/n1')));
    await assertSucceeds(getDocs(query(collection(db, 'offices/officeA/notifications'), where('recipientUid', '==', 'uidA'))));
  });
  it('não lista notificações sem filtrar pelo próprio uid', async () => {
    const db = env.authenticatedContext('uidA', advogadoA).firestore();
    await assertFails(getDocs(collection(db, 'offices/officeA/notifications')));
    await assertFails(getDoc(doc(db, 'offices/officeA/notifications/n2')));
  });
  it('pode marcar como lida, mas não alterar o conteúdo', async () => {
    const db = env.authenticatedContext('uidA', advogadoA).firestore();
    await assertSucceeds(updateDoc(doc(db, 'offices/officeA/notifications/n1'), { read: true, readAt: 1 }));
    await assertFails(updateDoc(doc(db, 'offices/officeA/notifications/n1'), { title: 'Alterado' }));
  });
  it('usuário de outro escritório não acessa', async () => {
    const db = env.authenticatedContext('uidA', advogadoB).firestore();
    await assertFails(getDoc(doc(db, 'offices/officeA/notifications/n1')));
  });
});

describe('Storage — acesso somente via backend', () => {
  it('nega upload e download diretos', async () => {
    const storage = env.authenticatedContext('uidA', advogadoA).storage();
    await assertFails(uploadString(ref(storage, 'offices/officeA/documents/d1/v1/a.pdf'), 'x'));
    await assertFails(getBytes(ref(storage, 'offices/officeA/documents/d1/v1/a.pdf')));
  });
});
