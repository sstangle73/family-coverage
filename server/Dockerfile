# Family Coverage server: Python's standard library only, so the image is Python, one file and the report page.
# Built from the repository root (compose.yaml sets the context), so the page comes from docs/report/.
FROM python:3.13-alpine
RUN adduser -D -u 10001 fc && mkdir -p /data && chown fc /data
COPY server/familycoverage_server.py /app/familycoverage_server.py
COPY docs/report/index.html /app/report.html
USER fc
ENV FC_DATA=/data FC_LISTEN=0.0.0.0:8745
VOLUME /data
EXPOSE 8745
HEALTHCHECK --interval=60s --timeout=5s CMD wget -qO- http://127.0.0.1:8745/healthz >/dev/null || exit 1
ENTRYPOINT ["python", "/app/familycoverage_server.py"]
CMD ["serve"]
