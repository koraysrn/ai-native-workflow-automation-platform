# Build stage: create the conda environment from environment.yml
FROM condaforge/miniforge3:latest AS builder
WORKDIR /app
COPY ai-layer/environment.yml environment.yml
RUN conda env create -f environment.yml && conda clean -afy

# Runtime stage
FROM condaforge/miniforge3:latest
COPY --from=builder /opt/conda/envs/awap-ai /opt/conda/envs/awap-ai
COPY ai-layer/app /app/app
WORKDIR /app
ENV PATH=/opt/conda/envs/awap-ai/bin:$PATH
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
