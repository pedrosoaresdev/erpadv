#!/bin/sh
# Gera /config.json (configuração PÚBLICA do Firebase Web + emuladores) a partir do ambiente.
# Nenhuma credencial administrativa passa por aqui.
set -eu
OUT=/usr/share/nginx/html/config.json

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

EMULATORS="null"
if [ -n "${EMULATOR_AUTH_URL:-}" ]; then
  EMULATORS=$(cat <<EOF
{ "authUrl": "$(json_escape "$EMULATOR_AUTH_URL")", "firestoreHost": "$(json_escape "${EMULATOR_FIRESTORE_HOST:-localhost}")", "firestorePort": ${EMULATOR_FIRESTORE_PORT:-8080} }
EOF
)
fi

cat > "$OUT" <<EOF
{
  "apiUrl": "/api",
  "firebase": {
    "apiKey": "$(json_escape "${FIREBASE_WEB_API_KEY:-demo-api-key}")",
    "authDomain": "$(json_escape "${FIREBASE_AUTH_DOMAIN:-demo-erpadv.firebaseapp.com}")",
    "projectId": "$(json_escape "${FIREBASE_PROJECT_ID:-demo-erpadv}")",
    "storageBucket": "$(json_escape "${FIREBASE_STORAGE_BUCKET:-demo-erpadv.appspot.com}")",
    "messagingSenderId": "$(json_escape "${FIREBASE_MESSAGING_SENDER_ID:-000000000000}")",
    "appId": "$(json_escape "${FIREBASE_APP_ID:-1:000000000000:web:0000000000000000}")"
  },
  "messaging": { "vapidKey": "$(json_escape "${FIREBASE_VAPID_KEY:-}")" },
  "emulators": $EMULATORS
}
EOF
echo "[erpadv] config.json gerado (projeto ${FIREBASE_PROJECT_ID:-demo-erpadv}, emuladores: $([ "$EMULATORS" = null ] && echo não || echo sim))."
