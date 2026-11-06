# Firebase Emulator Suite (Auth + Firestore + Storage + UI) para desenvolvimento e testes.
FROM node:24-alpine
RUN apk add --no-cache openjdk21-jre-headless bash curl \
    && npm install -g firebase-tools@15 --no-audit --no-fund \
    && addgroup -S firebase && adduser -S firebase -G firebase
WORKDIR /firebase
COPY --chown=firebase:firebase firebase/firebase.json firebase/.firebaserc firebase/firestore.rules \
     firebase/firestore.indexes.json firebase/storage.rules ./
RUN mkdir -p /data && chown firebase:firebase /data /firebase
USER firebase
# Baixa os binários dos emuladores na construção da imagem (start mais rápido e offline).
RUN firebase setup:emulators:firestore && firebase setup:emulators:storage && firebase setup:emulators:ui

EXPOSE 4000 4400 8080 9099 9199
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fs http://127.0.0.1:4400/emulators >/dev/null || exit 1

# Dados persistem no volume /data (importados no start e exportados ao parar).
CMD ["sh", "-c", "firebase emulators:start --project ${FIREBASE_PROJECT_ID:-demo-erpadv} --only auth,firestore,storage,ui --import=/data --export-on-exit=/data"]
