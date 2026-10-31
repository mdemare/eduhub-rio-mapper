# Prefer an installed CLI; bootstrap a pinned local copy when it is absent.
CLOJURE ?= $(or $(shell command -v clojure 2>/dev/null),python3 -B dev/clojure.py)

.PHONY: all jar lint proof-specs test test-rio test-all-units test-redis test-e2e test-v5 record-v5 record-v6 test-v6 test-common create-common-test watson antq clean
.PHONY: check full-check test-make test-fast test-v5-redis test-v6-redis test-v5-e2e test-v6-e2e playback-v5 playback-v6

all: lint proof-specs test watson clean jar

# Run once per ticket, within the ticket's check time budget.
check: test-make lint test-fast

# No time budget. Consider daily, after at least ten new commits since the
# last full check. Keep shared-service tests sequential, even with make -j.
full-check: check
	$(MAKE) proof-specs
	$(MAKE) playback-v5 playback-v6
	$(MAKE) test-redis
	$(MAKE) playback-ho-opleiding-v5 playback-ho-opleiding-v6
	$(MAKE) test-e2e
	$(MAKE) watson
	$(MAKE) jar

test-make:
	python3 -B -m unittest discover -s test -p 'test_*.py'

# Recorded interactions, Redis retries and live e2e tests belong to full-check.
# The original test target still runs all non-Redis, non-e2e legacy tests.
test-fast: test-common
	VCR_RECORD=false $(CLOJURE) -M:test-v5 v5-fast
	VCR_RECORD=false $(CLOJURE) -M:test-v6 v6-fast

test-redis:
	$(MAKE) test-v5-redis
	$(MAKE) test-v6-redis

test-e2e:
	$(MAKE) test-v5-e2e
	$(MAKE) test-v6-e2e

jar: target/eduhub-rio-mapper-v5.jar target/eduhub-rio-mapper-v6.jar

test-all-units:
	$(CLOJURE) -M:test-v5 v5
	$(CLOJURE) -M:test-v6 v6
	$(CLOJURE) -M:test-common common
	$(CLOJURE) -M:test-v5 v5-redis
	$(CLOJURE) -M:test-v6 v6-redis

lint:
	$(CLOJURE) -M:lint

proof-specs:
	$(CLOJURE) -M:proof-specs

test-rio:
	$(CLOJURE) -M:mapper test-rio rio-mapper-dev.jomco.nl --no-capture-output

test: test-v5 test-v6

test-v5: test-common
	$(CLOJURE) -M:test-v5 v5

test-v5-redis:
	$(CLOJURE) -M:test-v5 v5-redis

test-v5-e2e:
	$(CLOJURE) -M:test-v5 v5-e2e --no-capture-output

playback-v5:
	VCR_RECORD=false $(CLOJURE) -M:test-v5 v5-vcr

record-v5:
	rm -rf test-v5/fixtures/vcr/*
	OOAPI_VERSION=v5 VCR_RECORD=true $(CLOJURE) -M:test-v5 v5-vcr

test-v6: test-common
	$(CLOJURE) -M:test-v6 v6

test-v6-redis:
	$(CLOJURE) -M:test-v6 v6-redis

test-v6-e2e:
	$(CLOJURE) -M:test-v6 v6-e2e --no-capture-output

playback-v6:
	VCR_RECORD=false $(CLOJURE) -M:test-v6 v6-vcr

record-v6:
	rm -rf test-v6/fixtures/vcr/*
	OOAPI_VERSION=v6 VCR_RECORD=true $(CLOJURE) -M:test-v6 v6-vcr

test-common:
	$(CLOJURE) -M:test-common  common

export CLJ_WATSON_NVD_API_KEY=dummy # note: required but not used
export CLJ_WATSON_NVD_API_DATAFEED_URL=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz

watson:
	$(CLOJURE) -M:watson

antq:
	$(CLOJURE) -M:antq

generate-enums:
	$(CLOJURE) -M:dev -m generate-enums.main

beheren-edn:
	$(CLOJURE) -M:dev -m xsd-to-edn.main schema

types-edn:
	$(CLOJURE) -M:dev -m xsd-to-edn.main types

clean:
	rm -r target

target/eduhub-rio-mapper-v5.jar: $(shell find src-v5 src-common resources -type f)
	rm -rf target/classes-v5
	mkdir -p target/classes-v5
	$(CLOJURE) -M:v5 -e "(binding [*compile-path* \"target/classes-v5\"] (compile 'nl.surf.eduhub-rio-mapper.v5.main))"
	$(CLOJURE) -M:uberjar --aliases v5:package-v5 --main-class nl.surf.eduhub_rio_mapper.v5.main --target $@

target/eduhub-rio-mapper-v6.jar: $(shell find src-v6 src-common resources -type f)
	rm -rf target/classes-v6
	mkdir -p target/classes-v6
	$(CLOJURE) -M:v6 -e "(binding [*compile-path* \"target/classes-v6\"] (compile 'nl.surf.eduhub-rio-mapper.v6.main))"
	$(CLOJURE) -M:uberjar --aliases v6:package-v6 --main-class nl.surf.eduhub_rio_mapper.v6.main --target $@

# Read-only lookup recordings live outside the legacy VCR tree.
.PHONY: record-ho-opleiding-v5 playback-ho-opleiding-v5 record-ho-opleiding-v6 playback-ho-opleiding-v6

record-ho-opleiding-v5:
	VCR_RECORD=true $(CLOJURE) -M:test-v5 ho-opleiding-v5

playback-ho-opleiding-v5:
	VCR_RECORD=false $(CLOJURE) -M:test-v5 ho-opleiding-v5

record-ho-opleiding-v6:
	VCR_RECORD=true $(CLOJURE) -M:test-v6 ho-opleiding-v6

playback-ho-opleiding-v6:
	VCR_RECORD=false $(CLOJURE) -M:test-v6 ho-opleiding-v6
