#!/bin/sh
# Usa o certificado montado em /etc/nginx/certs (tls.crt / tls.key). Se não houver, gera um
# certificado autoassinado APENAS para desenvolvimento local.
set -eu
CERT_DIR=/etc/nginx/certs
mkdir -p "$CERT_DIR"
if [ ! -s "$CERT_DIR/tls.crt" ] || [ ! -s "$CERT_DIR/tls.key" ]; then
  echo "[erpadv] Certificado TLS não encontrado — gerando autoassinado para desenvolvimento."
  openssl req -x509 -nodes -newkey rsa:2048 -days 365 \
    -subj "/CN=${TLS_COMMON_NAME:-localhost}" \
    -addext "subjectAltName=DNS:${TLS_COMMON_NAME:-localhost},DNS:localhost,IP:127.0.0.1" \
    -keyout "$CERT_DIR/tls.key" -out "$CERT_DIR/tls.crt" >/dev/null 2>&1
  chmod 600 "$CERT_DIR/tls.key"
fi
