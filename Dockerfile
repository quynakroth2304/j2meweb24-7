# Dockerfile
FROM node:22-bookworm-slim

# Cài đặt OpenJDK 17 headless và bộ font hiển thị tiếng Việt
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    ca-certificates \
    fontconfig \
    fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    NODE_ENV=production \
    PORT=3000 \
    JWT_SECRET=j2me_cloud_docker_production_secret_key_2026

WORKDIR /app

# Cài đặt dependencies Node.js trước để tận dụng Docker layer cache
COPY package*.json ./
RUN npm ci --omit=dev

# Sao chép mã nguồn (đã loại trừ data/ qua .dockerignore)
COPY . .

# Đảm bảo freej2me.jar có mặt trong emulator/ và biên dịch sẵn Java lúc build Docker
RUN if [ ! -f emulator/freej2me.jar ] && [ -f public/lib/freej2me/freej2me.jar ]; then \
      cp -f public/lib/freej2me/freej2me.jar emulator/freej2me.jar; \
    elif [ -f freej2me.jar ]; then \
      cp -f freej2me.jar emulator/freej2me.jar; \
    fi && \
    javac -encoding UTF-8 -cp "emulator:emulator/freej2me.jar" -d emulator \
      emulator/javax/microedition/io/Connector.java \
      emulator/HeadlessJ2ME.java && \
    node -e "const fs=require('fs'),crypto=require('crypto');const h=crypto.createHash('md5');h.update(fs.readFileSync('emulator/HeadlessJ2ME.java'));h.update(fs.readFileSync('emulator/javax/microedition/io/Connector.java'));fs.writeFileSync('emulator/.headless_src_md5',h.digest('hex'));"

# Tạo user không phải root và cấp quyền ghi cho thư mục data/, logs/, emulator/
RUN groupadd -r j2me && useradd -r -g j2me -m j2me && \
    mkdir -p /app/data /app/logs && \
    chown -R j2me:j2me /app/data /app/logs /app/emulator

USER j2me

EXPOSE 3000 7860

CMD ["node", "server.js"]
