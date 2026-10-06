# Family Coverage server: Python, one file and the report page. The server itself needs only Python's standard library;
# h3 and requests are for the optional FCC comparison (FC_FCC): h3 for the hexagons, requests for the downloads, as
# the FCC's site turns Python's own urllib away. Debian slim rather than Alpine: h3 has no Alpine wheel for arm64, and
# home servers are often Raspberry Pis. Where h3 has no wheel at all (32-bit ARM), the image builds without it, and
# the FCC comparison says it's off. requests is pure Python, so it installs everywhere.
# Built from the repository root (compose.yaml sets the context), so the page comes from docs/report/.
FROM python:3.13-slim
RUN pip install --no-cache-dir --only-binary=:all: "requests==2.34.*" \
    && (pip install --no-cache-dir --only-binary=:all: h3==4.5.0 || echo "no h3 wheel: the FCC comparison is off") \
    && useradd --uid 10001 --create-home fc && mkdir -p /data && chown fc /data
COPY server/familycoverage_server.py /app/familycoverage_server.py
COPY docs/report/index.html /app/report.html
USER fc
ENV FC_DATA=/data FC_LISTEN=0.0.0.0:8745
VOLUME /data
EXPOSE 8745
# slim has no wget, so Python asks: any answer but 200, or none within the timeout, fails the check.
HEALTHCHECK --interval=60s --timeout=5s \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8745/healthz', timeout=4)"]
ENTRYPOINT ["python", "/app/familycoverage_server.py"]
CMD ["serve"]
