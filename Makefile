PYTHON312 ?= python3.12
WORKFLOW_PY := workflow-service/.venv/bin/python
RERANKER_PY := reranker-service/.venv/bin/python

.PHONY: test integration-test workflow-setup workflow-test reranker-setup reranker-test build dev public-release-check

test:
	mvn test
	$(MAKE) workflow-test
	$(MAKE) reranker-test

integration-test:
	mvn -Pintegration verify

workflow-setup:
	@if [ ! -x "$(WORKFLOW_PY)" ]; then \
		command -v "$(PYTHON312)" >/dev/null 2>&1 || { \
			echo "Python 3.12 is required; set PYTHON312=/path/to/python3.12" >&2; \
			exit 1; \
		}; \
		"$(PYTHON312)" -m venv workflow-service/.venv; \
	fi
	$(WORKFLOW_PY) -m pip install --upgrade pip
	$(WORKFLOW_PY) -m pip install -e './workflow-service[test]'

workflow-test: workflow-setup
	workflow-service/.venv/bin/ruff check workflow-service/src workflow-service/tests
	$(WORKFLOW_PY) -m compileall -q workflow-service/src workflow-service/tests
	$(WORKFLOW_PY) -m pytest workflow-service/tests -m 'not integration'

reranker-setup:
	@if [ ! -x "$(RERANKER_PY)" ]; then \
		command -v "$(PYTHON312)" >/dev/null 2>&1 || { \
			echo "Python 3.12 is required; set PYTHON312=/path/to/python3.12" >&2; \
			exit 1; \
		}; \
		"$(PYTHON312)" -m venv reranker-service/.venv; \
	fi
	$(RERANKER_PY) -m pip install --upgrade pip
	$(RERANKER_PY) -m pip install -r reranker-service/requirements-test.txt

reranker-test: reranker-setup
	$(RERANKER_PY) -m compileall -q reranker-service reranker-service/training reranker-service/tests
	$(RERANKER_PY) -m pytest reranker-service/tests

build:
	mvn -DskipTests package
	docker compose build

dev:
	docker compose up --build

public-release-check:
	bash scripts/verify-public-release.sh
