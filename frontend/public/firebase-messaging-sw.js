/* Service worker do Firebase Cloud Messaging (notificações push com a aba fechada).
 * A configuração PÚBLICA do Firebase chega pela query string do registro (ver PushService). */
importScripts('https://www.gstatic.com/firebasejs/12.19.0/firebase-app-compat.js');
importScripts('https://www.gstatic.com/firebasejs/12.19.0/firebase-messaging-compat.js');

const params = new URL(self.location.href).searchParams;
firebase.initializeApp({
  apiKey: params.get('apiKey'),
  projectId: params.get('projectId'),
  messagingSenderId: params.get('messagingSenderId'),
  appId: params.get('appId'),
});

const messaging = firebase.messaging();

// Mensagens com "notification" são exibidas automaticamente pelo SDK (com o link de webpush.fcmOptions).
messaging.onBackgroundMessage(() => undefined);

self.addEventListener('notificationclick', (event) => {
  const link = event.notification?.data?.FCM_MSG?.data?.link || '/';
  event.notification.close();
  event.waitUntil(self.clients.openWindow(link));
});
