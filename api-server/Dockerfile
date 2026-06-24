# ---- Build Stage ----
FROM node:20-alpine AS builder

WORKDIR /app

# Copy dependency manifests first for layer caching
COPY package*.json ./

# Install production dependencies only
RUN npm install --omit=dev --frozen-lockfile 2>/dev/null || npm install --omit=dev

# ---- Runtime Stage ----
FROM node:20-alpine

# Add non-root user for security
RUN addgroup -S guardian && adduser -S guardian -G guardian

WORKDIR /app

# Copy installed modules from builder
COPY --from=builder /app/node_modules ./node_modules

# Copy source code
COPY package*.json ./
COPY src/ ./src/

# Ensure correct ownership
RUN chown -R guardian:guardian /app

USER guardian

# Default port (can be overridden at runtime)
ARG PORT=3001
ENV PORT=${PORT}
ENV NODE_ENV=production

EXPOSE ${PORT}

# Healthcheck: hit the /health endpoint
HEALTHCHECK --interval=30s --timeout=10s --start-period=15s --retries=3 \
  CMD wget -qO- http://localhost:${PORT}/health || exit 1

CMD ["node", "src/index.js"]
