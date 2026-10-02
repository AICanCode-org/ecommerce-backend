# ShopFlow - Java track. Run `make help`.
MVN ?= mvn -B

.PHONY: help install lint test test-integration coverage run build
help:
	@echo "install | lint | test | test-integration | coverage | run | build"

install:
	$(MVN) -q dependency:go-offline

lint:
	$(MVN) checkstyle:check

test:
	$(MVN) test

test-integration:
	$(MVN) verify -Pintegration

coverage:
	$(MVN) verify -Pcoverage

# Local defaults so `make run` works next to `docker compose up -d postgres` (override any of them).
run: export JWT_SECRET ?= local-dev-secret-change-me-0123456789abcdef
run: export ADMIN_EMAIL ?= admin@shopflow.local
run: export ADMIN_PASSWORD ?= admin-password-123
run: export PAYMENT_WEBHOOK_SECRET ?= local-webhook-secret-change-me-0123456789
run:
	$(MVN) spring-boot:run

build:
	docker build -f Dockerfile -t shopflow-java ../..
