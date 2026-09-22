# Webhook Retry Engine.
#
#     make up      bring the whole engine up
#     make demo    walk through all five acceptance criteria against it
#     make logs    follow the services
#     make test    run the suite (needs Docker, or TEST_DB_URL -- see docs/LOCAL_DEV.md)
#     make down    stop everything and remove the database volume

COMPOSE := docker compose -f deploy/docker/docker-compose.yml

.PHONY: up down test demo logs ps

## Build and start everything, waiting until it is actually usable.
up:
	$(COMPOSE) up --build -d --wait
	@echo
	@echo "  ingest    http://localhost:8080/api/v1/events"
	@echo "  delivery  http://localhost:8081/internal/v1/deliveries"
	@echo "  receiver  http://localhost:8082/control/received"
	@echo
	@echo "  next: make demo"

## Stop everything. Removes the volume too, so the next `up` starts from an empty database.
down:
	$(COMPOSE) down -v

logs:
	$(COMPOSE) logs -f ingest-service delivery-service

ps:
	$(COMPOSE) ps

## The full suite. Testcontainers starts its own PostgreSQL, so this needs a Docker socket.
test:
	./mvnw clean verify

## Narrate all five acceptance criteria against a running stack -- this is the demo video.
demo:
	@./scripts/demo.sh

## Assert the same five, and fail loudly if any of them regress.
verify:
	@./scripts/verify-acceptance.sh
