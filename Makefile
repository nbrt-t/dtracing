SHELL := /bin/bash
MVN   := ./mvnw
DC    := docker compose
DC_SIM := docker compose -f docker-compose.simulator.yml

.PHONY: build clean compile test package \
        docker-build up down restart logs \
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
	$(DC) down --rmi local --remove-orphans 2>/dev/null || true
	$(DC_SIM) down --rmi local 2>/dev/null || true

codegen: ## Regenerate SBE codecs in common module
	$(MVN) -B compile -pl common

# ── Docker ──────────────────────────────────────────────────────────────────

docker-build: ## Build all Docker images
	$(DC) build
	$(DC_SIM) build

up: ## Start all market-data-handler instances (detached)
	$(DC) up --build -d

down: ## Stop all market-data-handler instances
	$(DC) down

restart: down up ## Restart all handlers

logs: ## Tail logs from all handlers
	$(DC) logs -f

logs-%: ## Tail logs for one handler (e.g. make logs-euronext)
	$(DC) logs -f mdh-$*

# ── Simulator ───────────────────────────────────────────────────────────────

simulate: ## Run simulator at real-time speed
	$(DC_SIM) up --build

simulate-fast: ## Run simulator at max speed
	$(DC_SIM) run --rm -e SIMULATOR_SPEEDMULTIPLIER=1000 simulator

# ── Status ──────────────────────────────────────────────────────────────────

status: ## Show running containers and ports
	$(DC) ps
	@echo "---"
	$(DC_SIM) ps 2>/dev/null || true

# ── Help ────────────────────────────────────────────────────────────────────

help: ## Show this help
	@grep -E '^[a-zA-Z_%-]+:.*?## ' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2}'