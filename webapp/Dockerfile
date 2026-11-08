FROM python:3.12-slim
RUN useradd --system --create-home --uid 10002 web && mkdir /data && chown web /data
WORKDIR /app
COPY server.py .
USER web
ENV PORT=8090 WEBAPP_DB=/data/webapp.db
EXPOSE 8090
CMD ["python", "-u", "server.py"]
