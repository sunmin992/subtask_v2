FROM python:3.12-slim
RUN pip install --no-cache-dir simpy==4.1.1
WORKDIR /model
COPY processor.py /model/processor.py
USER 65534:65534
ENTRYPOINT ["python", "-B", "/model/processor.py"]
