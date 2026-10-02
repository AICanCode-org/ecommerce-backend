# ShopFlow - TypeScript track. Run `make help`.
.PHONY: help install lint test test-integration coverage run build
help:
	@echo "install | lint | test | test-integration | coverage | run | build"

install:
	npm ci --no-audit --no-fund

lint:
	npm run -s lint

test:
	npm test

test-integration:
	npm run test:integration

coverage:
	npm run coverage

# Local defaults so `make run` works next to `docker compose up -d postgres` (override any of them).
run: export JWT_SECRET ?= local-dev-secret-change-me-0123456789abcdef
run: export ADMIN_EMAIL ?= admin@shopflow.local
run: export ADMIN_PASSWORD ?= admin-password-123
run: export PAYMENT_WEBHOOK_SECRET ?= local-webhook-secret-change-me-0123456789
run:
	npm run dev

build:
	npm run build
	docker build -f Dockerfile -t shopflow-typescript ../..
