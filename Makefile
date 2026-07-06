.PHONY: up down build test migrate seed-embeddings

up:
	podman compose -f infra/podman/compose.yaml up -d

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
