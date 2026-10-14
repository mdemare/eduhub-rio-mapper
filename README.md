# SURFeduhub RIO mapper

## Project Structure

The mapper is built as two separate applications, one for OOAPI v5 and
one for OOAPI v6, from a separated source tree structure that
distinguishes between version-specific code and common/shared code:

- `src-v5/` - Version 5 specific implementation code
- `test-v5/` - Version 5 specific tests
- `src-v6/` - Version 6 specific implementation code
- `test-v6/` - Version 6 specific tests
- `src-common/` - Shared/common code (reusable across versions)
- `test-common/` - Tests for common code

This separation ensures clear architectural boundaries: `src-v5` and
`src-v6` do not use each other, and common code does not depend on
version-specific code. Each version has its own build
(`target/eduhub-rio-mapper-v5.jar` and
`target/eduhub-rio-mapper-v6.jar`, see `make jar`) and Dockerfile (see
[Docker containers](#docker-containers)). See
[doc/ooapi-v6-plan.md](doc/ooapi-v6-plan.md) for the background.

### Testing Common Code Isolation

To validate that common code has no version-specific dependencies, run:
```sh
make test-common
```

This runs tests with **only** `src-common` on the classpath (excluding `src-v5` and `src-v6`). If any common code depends on version-specific namespaces, the test will fail with a `FileNotFoundException`.

## Documentation

### Included

- [Design](doc/design/rio-mapper-ontwerp.pdf)
- [RIO information model](doc/RIO-Informatiemodel/Informatiemodel_RIO.pdf)
- [RIO read webservices](doc/RIO-Webservicekoppeling-Beheren-en-Raadplegen/Webservice_documentatie_-_DUO_RIO_Raadplegen_OnderwijsOrganisatie_V4.pdf)
- [RIO manage webservices](doc/RIO-Webservicekoppeling-Beheren-en-Raadplegen/Webservice_documentatie_-_DUO_RIO_Beheren_OnderwijsOrganisatie_V4.pdf)

### External

- [OOAPI repository](https://github.com/open-education-api/specification)
- [OOAPI doc](https://openonderwijsapi.nl/specification/v5/docs.html)
- [OOAPI doc/consumers-and-profiles/rio](https://openonderwijsapi.nl/#/technical/consumers-and-profiles/rio)

# Goal for project

Purpose 1: mapper function to transfer top-level OOAPI objects
to RIO objects (one directional). This works purely with clojure data
structures (does not make file IO or API calls).

RIO objects are ultimately serialized to XML.

OOAPI objects arrive as JSON.

This concerns the following top-level OOAPI objects:

- Education Specification

- Course / Program (with related Offerings)

We'll start with the EducationSpecification because this is it
simplest.

# Data flow (final app)

- Get OOAPI JSON via Edhuhub Gateway

- OOAPI JSON -> OOAPI Data (via standard json -> clojure reader)

- Validate OOAPI data with spec, error if not valid

- Get RIO Ids map for all relevant RIO resolver API objects.

- OOAPI Data + RIO IDs -> RIO Data (pure function)

- Validate RIO Data (for testing, in dev we use generative
  testing and then this could be disabled in prod)

- RIO Data serialization to RIO XML (perhaps via hiccup-style
  "between format")

- Validate RIO XML using XSD

- Post RIO XML to API# Implementation steps (mapper without API calls)

## Mapper of pure data

- Write down example of OOAPI data

- example of RIO Data equivalent of the above OOAPI Data

- create specs for OOAPI Data. Spec contains checks for required fields
  and types etc. The spec may be stricter than the OOAPI swagger
  definition if necessary for the translation (e.g. certain
  attributes may then be required for the mapper).

- create specs for RIO Data (clojure idiomatic, easy to spec)

- implementation mapper from `EducationSpecification` OOAPI Data to
  RIO Data, with tests

- RIO XML serialization of the above RIO Data

- Validate Rio XML with given XSDs

- implementation mapper from `Education` OOAPI Data to RIO Data, with
  tests.

- RIO XML serialization of the above RIO Data

- Validate Rio XML with given XSDs

If problems arise during the above that require adjustments
the APIs are needed, we will discuss this with Surf so that they can be implemented
adjustments can be scheduled. The aim is that adjustments are not made
more than 2 iterations of the specs are implemented. We have to do this
so batch and document well. How exactly should we look? In
We then continue working in the meantime (leave certain attributes out
consideration / simplifying, etc.), preferably with data that meets the requirements
existing specs.

## Api calls

- Implement test/mock APIs (OOAPI and Rio), find out how and what is
  necessary for the steps below. For example defining the OOAPI Client and Rio
  client as protocol, and making separate implementations for testing purposes.

- Make OOAPI API calls to collect data based on "path":
  setting url/id, type of root object (Education of
  EducationSpecification), root object id.

- Make RIO resolver API calls to translate OOAPI id to RIO id.

- Make RIO API calls to perform RIO XML CRUD actions.

Distinguish between retryable and non-retryable calls for all API calls.
retryable errors. This must be processed by the caller of the mapper function
become.

## Demo app

- Demo CLI app; given configuration and OOAPI path arguments it should be able to perform
- a mapping against the OOAPI and RIO APIs. Possibly with retries in case of errors.

## Create keystore

During development, a keystore.jks file is needed in the root of the
project. Don't add it to git! In order to generate the keystore, run:

```sh
sh dev/create-keystore.sh
```

The truststore is included in this repo as `resources/truststore.jks`
and loaded from the classpath. To regenerate it, run:

```sh
sh dev/create-truststore.sh
```

The script needs OpenSSL 3.2 or newer, because keytool cannot create
a store with the empty password the application uses.

## Configuration

The application is configured with environment variables. The
gateway variables have a `_V5` or `_V6` suffix, depending on which
mapper is run. Run the `document-env-vars` command to list the
variables and their defaults.

```
API_HOSTNAME                        Hostname for listing web API
API_PORT                            HTTP port for serving web API
CLIENTS_INFO_PATH                   Clients info config file
CONNECTION_TIMEOUT_MILLIS           HTTP connection timeout in milliseconds
GATEWAY_PASSWORD_V5                 OOAPI Gateway Password (v5 mapper)
GATEWAY_PASSWORD_V6                 OOAPI Gateway Password (v6 mapper)
GATEWAY_ROOT_URL_V5                 OOAPI Gateway Root URL (v5 mapper)
GATEWAY_ROOT_URL_V6                 OOAPI Gateway Root URL (v6 mapper)
GATEWAY_USER_V5                     OOAPI Gateway Username (v5 mapper)
GATEWAY_USER_V6                     OOAPI Gateway Username (v6 mapper)
JOB_MAX_RETRIES                     Max number of retries of a failed job
JOB_RETRY_WAIT_MS                   Number of milliseconds to wait before retrying a failed job
KEYSTORE                            Path to keystore
KEYSTORE_ALIAS                      Key alias in keystore
KEYSTORE_PASSWORD                   Keystore password
REDIS_KEY_PREFIX                    Prefix for redis keys
REDIS_URI                           URI to redis
RIO_READ_URL                        RIO Services Read URL
RIO_RECIPIENT_OIN                   Recipient OIN for RIO SOAP calls
RIO_RETRY_ATTEMPTS_SECONDS          Number of seconds to wait for first, second, etc. retry of RIO command, comma separated
RIO_UPDATE_URL                      RIO Services Update URL
STATUS_TTL_SEC                      Number of seconds to keep job status
STORE_HTTP_REQUESTS                 Boolean; should all http traffic be logged? Defaults to true.
SURF_CONEXT_CLIENT_ID               SurfCONEXT client id for Mapper service
SURF_CONEXT_CLIENT_SECRET           SurfCONEXT client secret for Mapper service
SURF_CONEXT_INTROSPECTION_ENDPOINT  SurfCONEXT introspection endpoint
WORKER_API_HOSTNAME                 Hostname for listing worker web API
WORKER_API_PORT                     HTTP port for serving worker web API
```

The truststore is not configurable; it is always loaded from the
classpath resource `truststore.jks` (`resources/truststore.jks`).

The `CLIENTS_INFO_PATH` should specify a json file with settings for client-id, schac-home and oin:

```json
{
  "clients": [{
    "client-id": "rio-mapper-dev.jomco.nl",
    "institution-schac-home": "demo06.test.surfeduhub.nl",
    "institution-oin": "0000000700025BE00000",
    "institution-name": "University of Uitgeest"
  }]
}
```

Only `client-id` is required. A client without `institution-oin` can
only use the read-only API endpoints.

## Docker containers

The application consists of two parts: the *API* and the
*Worker*. Both components can be started from a single docker image.
There is a separate Dockerfile per OOAPI version:
[Dockerfile.v5](Dockerfile.v5) and [Dockerfile.v6](Dockerfile.v6).  The image build
is *nonroot* variant of *distroless* Debian which runs the CLI as:

- *user*: `nonroot` (uid=65532)
- *group*: `nonroot` (guid=65532)

See for the configuration options the [./CLI.md](CLI) documentation
and note that these containers both need access to the same *redis*
server.

Build the docker image with:

```sh
docker build -f Dockerfile.v6 -t eduhub-rio-mapper .
```

(use `-f Dockerfile.v5` for the v5 mapper).

To run the API server:

```sh
docker run \
  -p 8080:8080 \
  -e API_HOSTNAME=0.0.0.0 \
  -e API_PORT=8888 \
  -v config:/config \
  --env-file config.env \
  eduhub-rio-mapper \
  serve-api
```

and to run the worker:

```sh
docker run \
  -v config:/config \
  --env-file config.env \
  eduhub-rio-mapper \
  worker
```

Notice that `config.env` is not included in the repo, and that the configuration files should be made
available via a "volume" (see `-v` option).

### Docker compose

For local development, [docker-compose.yml](./docker-compose.yml) runs
redis, prometheus, the API and the worker:

```sh
docker compose up --build
```

The API and worker images are built locally from `Dockerfile.v6`; set
`OOAPI_VERSION=v5` to build from `Dockerfile.v5` instead. The images
are built for the host's platform.

The following files, which are not in git, must be present in the root
of the project:

- `.envrc`: the environment configuration (see
  [Configuration](#configuration)), read as the `env_file` of the API
  and worker. Lines may use the direnv `export NAME=value` form.
  Use `KEYSTORE=/keystore.jks`; `CLIENTS_INFO_PATH`, `REDIS_URI`,
  `API_HOSTNAME` and `API_PORT` are set by the compose file.
- `keystore.jks`: mounted as `/keystore.jks` (see [Create
  keystore](#create-keystore)).

The truststore is included in the image (`resources/truststore.jks`).

## End to End tests

See [doc/e2e.md](./doc/e2e.md).

## OpenTelemetry

The RIO Mapper supports Open Telemetry and the docker containers include the [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation).  It provides JVM related metrics for monitoring.

The *worker* will add extra metrics:

- `rio_mapper_http_requests_total` counters per status, schac-home and institution name
- `rio_mapper_active_and_queued_job_count` gauge for queue length per institution

This agent is configured using the following environment variables:

 - `OTEL_METRICS_EXPORTER`
 - `OTEL_EXPORTER_PROMETHEUS_ENDPOINT`
 - `OTEL_SERVICE_NAME`
 - `OTEL_LOGS_EXPORTER`
 - `OTEL_TRACES_EXPORTER`

 For example values see [docker-compose.yml](./docker-compose.yml).

# Reporting vulnerabilities

If you have found a vulnerability in the code, we would like to hear about it so that we can take appropriate measures as quickly as possible. We are keen to cooperate with you to protect users and systems better. See https://www.surf.nl/.well-known/security.txt for information on how to report vulnerabilities responsibly.

# License

Copyright (C) 2022-2025 SURFnet B.V.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful, but
WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public
License along with this program.  If not, see
<https://www.gnu.org/licenses/>.
