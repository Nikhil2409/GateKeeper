# syntax=docker/dockerfile:1
# Builds one of the React UIs and serves it with nginx. Context = this folder; the UI app comes in as the named
# context "app" (see docker-compose.yml), and only its sources are copied, never a local node_modules.
# The VITE_* settings are baked in at build time: the browser calls the services on the ports compose publishes.
FROM node:22-alpine AS build
WORKDIR /app
COPY --from=app package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY --from=app index.html vite.config.js ./
COPY --from=app src ./src
ARG VITE_API_BASE_URL
ARG VITE_GATEWAY_URL
ARG VITE_AUTH_API_URL
ARG VITE_SERVICE_API_URL
ARG VITE_USE_MOCK_DATA=false
ARG VITE_GRAFANA_URL
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL VITE_GATEWAY_URL=$VITE_GATEWAY_URL VITE_AUTH_API_URL=$VITE_AUTH_API_URL \
    VITE_SERVICE_API_URL=$VITE_SERVICE_API_URL VITE_USE_MOCK_DATA=$VITE_USE_MOCK_DATA VITE_GRAFANA_URL=$VITE_GRAFANA_URL
RUN npm run build

FROM nginx:1.27-alpine
COPY spa.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
