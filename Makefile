.PHONY: up down build test migrate seed-embeddings ingest-corpus eval-validate eval eval-score eval-agreement

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

EVAL_QUESTIONS ?= src/test/resources/eval/questions.jsonl

# Check that every expected passage in the question set exists in the ingested corpus.
eval-validate:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments="eval-validate --questions=$(EVAL_QUESTIONS)"

# Run the question set. Pipeline settings come from the environment, for example:
#   SHELTR_SEARCH_MODE=hybrid SHELTR_SEARCH_RERANKER=model make eval CONFIG=hybrid-rerank
eval:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments="eval-run --config=$(CONFIG) --questions=$(EVAL_QUESTIONS) --out=../../eval/reports"

# Score a run. Use a profile whose chat model differs from the one that produced the run:
#   SPRING_PROFILES_ACTIVE=gemini make eval-score RAW=../../eval/reports/<file>.raw.json
eval-score:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments="eval-score --raw=$(RAW) --questions=$(EVAL_QUESTIONS)"

eval-agreement:
	cd apps/backend && mvn -q spring-boot:run -Dspring-boot.run.arguments="eval-agreement --spotcheck=$(SPOTCHECK)"
