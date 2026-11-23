In order to run the end-to-end tests, the following things should have been taken care of:

- RIO access (see [test/test-clients.json](test/test-clients.json))

    - `CLIENTS_INFO_PATH` (= `test/test-clients.json`)

    - `KEYSTORE` (including `rio_test_surfeduhub_surf_nl` certificate with key, see also [README](../README.md) for instructions to generate this file)
    - `KEYSTORE_ALIAS` (= `test-surf`)
    - `KEYSTORE_PASSWORD`

    - `TRUSTSTORE` (= `truststore.jks`, already part of this repository)
    - `TRUSTSTORE_PASSWORD` (= `xxxxxx`)

    - `RIO_RECIPIENT_OIN`
    - `RIO_READ_URL`
    - `RIO_UPDATE_URL`

- SURFconext access for client ID "rio-mapper-dev.jomco.nl" (see also [test/test-clients.json](test/test-clients.json))

    - `SURF_CONEXT_CLIENT_ID`
    - `SURF_CONEXT_CLIENT_SECRET`
    - `SURF_CONEXT_INTROSPECTION_ENDPOINT`

    - `CLIENT_ID` (= `rio-mapper-dev.jomco.nl`, SURFconext account with access to `SURF_CONEXT_CLIENT_ID`)
    - `CLIENT_SECRET`
    - `TOKEN_ENDPOINT` (URL to SURFconext token endpoint)

- access to SURF SWIFT Object Store

    - `OS_USERNAME`
    - `OS_PASSWORD`
    - `OS_AUTH_URL`
    - `OS_PROJECT_NAME`
    - `OS_CONTAINER_NAME`

- an application account on the test gateway which provides access to the above Object Store

    - `GATEWAY_ROOT_URL_V5`
    - `GATEWAY_USER_V5`
    - `GATEWAY_PASSWORD_V5`
    - `GATEWAY_ROOT_URL_V6`
    - `GATEWAY_USER_V6`
    - `GATEWAY_PASSWORD_V6`

If the mapper is running locally (happens automatically):

- a locally accessible *redis* server is running

When the above has been done, tests can be run using:

```sh
make test-e2e
```

For "live" output run:

```sh
make test-e2e TEST_OPTS=--no-capture-output
```

For debugging set environment variable `STORE_HTTP_REQUESTS` to `true` to get extra information about the API calls done by the mapper.

See also [e2e GH workflow](../.github/workflows/e2e.yml).

## Ho variant interaction scenarios

The dedicated Ho variant interaction scenario covers insertion, an unchanged
upsert, changed validity dates, and validity intersections. The intersection
cases exercise both entities supplying the tighter bounds, either end date
being absent, and both end dates being absent. v6 supplies datetime values and
asserts date-only RIO values.

Two additional recorded checks call the relation handler with real OOAPI and
RIO clients: first with an unresolved child, then with its already-confirmed
RIO code, as supplied by the mapper's confirm phase. They assert the exact
parent OOAPI URL and gateway route, the institution OIN and external key in
each resolution request, and the resolved RIO endpoints. The second check
must resolve only the parent, without resolving the child again. These calls
read existing data and require no relation mutations.

Unavailable-parent cases use real missing/corrupt OOAPI fixtures, an unlinked
parent identity, and a rejected RIO relation read. They assert no RIO mutation
requests and read the variant back to confirm its dates and existing relation
are unchanged. The attempted updates use dates that would otherwise prune
that relation. Fixture faults are restored outside VCR recording.

Variant reconciliation fails with these explicit errors:

| Error/reason | Meaning | Retryable |
| --- | --- | --- |
| `relation-parent-unavailable` / `missing-ooapi-parent` | Parent OOAPI data is absent or returns 404 | No |
| `relation-parent-unavailable` / `unresolved-parent` | Parent external key has no RIO identity | No |
| `relation-parent-unavailable` / `parent-read-failed` | Parent data or identity cannot be read | Yes, unless the provider marks the failure permanent or denies access |
| `relation-read-failed` | RIO rejected the relation snapshot read | Yes |

Parent errors carry the parent identity, institution OIN and original cause.
Job errors expose the explicit reason in their message, the failing phase and
retryability. Before pruning a variant's existing relations, the mapper loads
and validates the parent and reuses that verified data during reconciliation.
A rejected snapshot read is never treated as an empty set of relations.

These cases change the OOAPI fixtures for the parent and variant between
upserts. They never upsert or change the dates of the accredited RIO parent;
the final case reads that parent back to verify its dates are unchanged.
The fixture parent windows start on 2018-01-01, so select an accredited parent
whose actual validity includes these windows and has no end date.

With the live environment loaded, record and replay using:

```sh
HO_VARIANT_PARENT_CODE=1001O3338 make record-ho-variant-v5
make playback-ho-variant-v5
```

Use the corresponding `-v6` targets for v6. Fixture uploads happen only in
record mode, outside VCR recording. Playback uses the recorded OOAPI responses
and never uploads fixtures or contacts RIO.
