SHELL := /bin/bash
MVN   := ./mvnw
DC    := docker compose
DC_SIM := docker compose --profile simulator

# Overridable ports (e.g. make infra GRAFANA_PORT=3002)
export GRAFANA_PORT    ?= 3001
export PROMETHEUS_PORT ?= 9090

.PHONY: build clean compile test package \
        docker-build up up-debug down restart logs \
        infra infra-down clean-traces \
        simulate simulate-fast \
        status help

# ── Maven ───────────────────────────────────────────────────────────────────

build: ## Full Maven build (clean install)
	$(MVN) -B clean install

compile: ## Compile all modules (no tests)
	$(MVN) -B compile

test: ## Run tests
	$(MVN) -B test

package: ## Package all modules (skip tests)
	$(MVN) -B clean package -DskipTests

clean: ## Maven clean + remove Docker images
	$(MVN) -B clean
	$(DC_SIM) down --rmi local --remove-orphans 2>/dev/null || true

codegen: ## Regenerate SBE codecs in common module
	$(MVN) -B compile -pl common

# ── Docker ──────────────────────────────────────────────────────────────────

docker-build: ## Build all Docker images
	$(DC_SIM) build

up: ## Start all market-data-handler instances (detached)
	$(DC) up --build -d --remove-orphans

up-debug: ## Start all instances with DEBUG logging
	LOGGING_LEVEL_ROOT=DEBUG $(DC) up --build -d --remove-orphans

down: ## Stop all market-data-handler instances
	$(DC) down --remove-orphans

restart: down up ## Restart all handlers

logs: ## Tail logs from all handlers
	$(DC) logs -f

logs-%: ## Tail logs for one handler (e.g. make logs-euronext)
	$(DC) logs -f mdh-$*

# ── Observability ──────────────────────────────────────────────────────────

infra: ## Start only observability stack (Tempo, Grafana, Prometheus)
	$(DC) up -d --remove-orphans tempo grafana prometheus

infra-down: ## Stop only observability stack
	$(DC) stop tempo grafana prometheus
	$(DC) rm -f tempo grafana prometheus

clean-traces: ## Delete all stored traces and Grafana data
	$(DC) stop tempo grafana
	docker volume rm -f dtracing_tempo-data dtracing_grafana-data
	@echo "Trace and Grafana data cleared. Run 'make infra' or 'make up' to restart."

# ── Simulator ───────────────────────────────────────────────────────────────

simulate: ## Run simulator at real-time speed
	$(DC_SIM) run --rm --build simulator

simulate-fast: ## Run simulator at max speed
	$(DC_SIM) run --rm -e SIMULATOR_SPEEDMULTIPLIER=1000 simulator

# ── Status ──────────────────────────────────────────────────────────────────

status: ## Show running containers and ports
	$(DC_SIM) ps 2>/dev/null || true

# ── Help ────────────────────────────────────────────────────────────────────

help: ## Show this help
	@grep -E '^[a-zA-Z_%-]+:.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'