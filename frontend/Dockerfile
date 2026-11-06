# Contexto de build: raiz do repositório (usa docker/nginx).
# ---------------------------------------------------------------- build
FROM node:24-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npx ng build --configuration production

# ---------------------------------------------------------------- runtime
FROM nginx:1.27-alpine
RUN apk add --no-cache openssl
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
# config.json é gerado no start a partir de variáveis de ambiente (docker/nginx/30-runtime-config.sh)
RUN rm -f /usr/share/nginx/html/config.json
COPY docker/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY docker/nginx/25-tls-certificate.sh docker/nginx/30-runtime-config.sh /docker-entrypoint.d/
RUN chmod +x /docker-entrypoint.d/25-tls-certificate.sh /docker-entrypoint.d/30-runtime-config.sh \
    && rm -f /etc/nginx/conf.d/default.conf

EXPOSE 80 443
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1/healthz || exit 1
