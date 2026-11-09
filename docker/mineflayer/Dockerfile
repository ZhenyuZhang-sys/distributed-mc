# 第 9 步 9.1：协议层的原版客户端（mineflayer，版本写死在 package-lock.json）。只给测试用。
FROM node:20.18.1-bookworm-slim
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts && npm cache clean --force
COPY join.js ./
ENTRYPOINT ["node", "join.js"]
