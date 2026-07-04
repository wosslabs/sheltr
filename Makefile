.PHONY: up down build test

up:
	docker compose -f infra/docker/docker-compose.yml up -d

down:
	docker compose -f infra/docker/docker-compose.yml down

build:
	cd apps/backend && mvn -q -DskipTests package

test:
	cd apps/backend && mvn -q test
