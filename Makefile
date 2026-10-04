.PHONY: up down build test migrate seed-embeddings ingest-corpus

up:
	podman compose -f infra/podman/compose.yaml up -d --wait
ifeq ($(SHELTR_PROVIDER),gemini)
	@echo "SHELTR_PROVIDER=gemini — skipping local Ollama model pull"
else
	podman exec sheltr-ollama ollama pull qwen2.5:3b-instruct
	podman exec sheltr-ollama ollama pull nomic-embed-text:v1.5
endif

down:
	podman compose -f infra/podman/compose.yaml down

build:
	cd apps/backend && mvn -q -DskipTests package

test:
	cd apps/backend && mvn -q test

migrate:
	cd apps/backend && mvn -q flyway:migrate

seed-embeddings:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments=seed-embeddings

ingest-corpus:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments=ingest-corpus
