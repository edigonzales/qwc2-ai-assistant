FROM python:3.12.14-slim
WORKDIR /app
COPY assistant/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY shared/limits.json /shared/limits.json
COPY assistant/app.py assistant/diagnostics.py assistant/intents.py assistant/schemas.py .
USER 65534:65534
EXPOSE 8080
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080", "--no-access-log"]
