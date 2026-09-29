# syntax=docker/dockerfile:1
# ============================================================
# buddy-ui 前端镜像：多阶段（Node 构建 → nginx 静态托管 + /api 反代到 backend）。
# 构建：docker build -t buddy-ui:latest ./buddy-ui
# ============================================================

# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /build
COPY package*.json ./
RUN npm ci --legacy-peer-deps
COPY . .
RUN npm run build

# ---------- 运行阶段（nginx） ----------
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://localhost/ >/dev/null 2>&1 || exit 1
