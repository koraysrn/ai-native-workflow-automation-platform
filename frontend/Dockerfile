# Build stage (monorepo context: the repository root is the build context)
FROM node:24-alpine AS base
RUN corepack enable

FROM base AS deps
WORKDIR /app
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

FROM base AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY frontend/ ./
RUN pnpm build

# Runtime stage
FROM node:24-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/.next ./.next
COPY --from=builder /app/node_modules ./node_modules
COPY --from=builder /app/package.json ./package.json
# The runtime reads Cache Components / per-request rendering settings from the
# Next config. Without it, nonce-aware dynamic routes fail with DYNAMIC_SERVER_USAGE.
COPY --from=builder /app/next.config.ts ./next.config.ts
EXPOSE 3000
CMD ["node", "node_modules/next/dist/bin/next", "start"]
