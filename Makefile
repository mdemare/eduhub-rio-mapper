.PHONY: all jar lint proof-specs test test-rio test-all-units test-redis test-e2e test-v5 record-v5 record-v6 test-v6 test-common create-common-test watson antq clean check-docker-compose

all: lint proof-specs test watson clean jar

.PHONY: test-v5-e2e test-v6-e2e

# The suites share service ports, so run them sequentially even with make -j.
test-e2e:
	$(MAKE) test-v5-e2e
	$(MAKE) test-v6-e2e

.PHONY: test-v5-redis test-v6-redis

# The suites purge shared Redis keys, so run them sequentially even with make -j.
test-redis:
	$(MAKE) test-v5-redis
	$(MAKE) test-v6-redis

jar: target/eduhub-rio-mapper-v5.jar target/eduhub-rio-mapper-v6.jar

test-all-units:
	clojure -M:test-v5 v5
	clojure -M:test-v6 v6
	clojure -M:test-common common
	clojure -M:test-v5 v5-redis
	clojure -M:test-v6 v6-redis

lint:
	clojure -M:lint

proof-specs:
	clojure -M:proof-specs

test-rio:
	clojure -M:mapper test-rio rio-mapper-dev.jomco.nl --no-capture-output

test: test-v5 test-v6

test-v5: test-common
	clojure -M:test-v5 v5

test-v5-redis:
	clojure -M:test-v5 v5-redis

test-v5-e2e:
	clojure -M:test-v5 v5-e2e --no-capture-output

playback-v5:
	clojure -M:test-v5 v5-vcr

record-v5:
	rm -rf test-v5/fixtures/vcr/*
	OOAPI_VERSION=v5 VCR_RECORD=true clojure -M:test-v5 v5-vcr

test-v6: test-common
	clojure -M:test-v6 v6

test-v6-redis:
	clojure -M:test-v6 v6-redis

test-v6-e2e:
	clojure -M:test-v6 v6-e2e --no-capture-output

playback-v6:
	clojure -M:test-v6 v6-vcr

record-v6:
	rm -rf test-v6/fixtures/vcr/*
	OOAPI_VERSION=v6 VCR_RECORD=true clojure -M:test-v6 v6-vcr

test-common:
	clojure -M:test-common  common

export CLJ_WATSON_NVD_API_KEY=dummy # note: required but not used
export CLJ_WATSON_NVD_API_DATAFEED_URL=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz

watson:
	clojure -M:watson

antq:
	clojure -M:antq

generate-enums:
	clojure -M:dev -m generate-enums.main

beheren-edn:
	clojure -M:dev -m xsd-to-edn.main schema

types-edn:
	clojure -M:dev -m xsd-to-edn.main types

clean:
	rm -r target

check-docker-compose:
	sh dev/check-docker-compose.sh

target/eduhub-rio-mapper-v5.jar: $(shell find src-v5 src-common resources -type f)
	rm -rf target/classes-v5
	mkdir -p target/classes-v5
	clojure -M:v5 -e "(binding [*compile-path* \"target/classes-v5\"] (compile 'nl.surf.eduhub-rio-mapper.v5.main))"
	clojure -M:uberjar --aliases v5:package-v5 --main-class nl.surf.eduhub_rio_mapper.v5.main --target $@

target/eduhub-rio-mapper-v6.jar: $(shell find src-v6 src-common resources -type f)
	rm -rf target/classes-v6
	mkdir -p target/classes-v6
	clojure -M:v6 -e "(binding [*compile-path* \"target/classes-v6\"] (compile 'nl.surf.eduhub-rio-mapper.v6.main))"
	clojure -M:uberjar --aliases v6:package-v6 --main-class nl.surf.eduhub_rio_mapper.v6.main --target $@
