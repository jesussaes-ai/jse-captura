FROM node:22-alpine
# ffmpeg/ffprobe: validar MP4 y generar miniaturas dentro del NAS (sin Internet en ejecución).
RUN apk add --no-cache ffmpeg tini
WORKDIR /app
COPY package.json ./
COPY src ./src
ENV JSE_DATOS=/datos JSE_GRABACIONES=/grabaciones JSE_PUERTO=8790 NODE_ENV=production
EXPOSE 8790
USER node
HEALTHCHECK --interval=60s --timeout=5s CMD wget -qO- http://127.0.0.1:8790/api/v1/salud || exit 1
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["node", "src/main.mjs"]
