# syntax=docker/dockerfile:1
# Build context = repository root (so the shared spec/db/migrations can be copied):
#   docker build -f tracks/typescript/Dockerfile -t shopflow-typescript .

# ---- build stage: install all deps and compile TypeScript ----
FROM node:22-alpine AS build
WORKDIR /build
COPY tracks/typescript/package.json tracks/typescript/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tracks/typescript/tsconfig.json tracks/typescript/tsconfig.build.json ./
COPY tracks/typescript/src ./src
RUN npm run build

# ---- runtime stage: production deps + compiled JS only ----
FROM node:22-alpine
ENV NODE_ENV=production PORT=8080 MIGRATIONS_DIR=/app/migrations
WORKDIR /app
COPY tracks/typescript/package.json tracks/typescript/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY --from=build /build/dist ./dist
COPY spec/db/migrations ./migrations
# The official image ships an unprivileged "node" user (uid 1000).
USER node
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8080/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
CMD ["node", "dist/index.js"]
