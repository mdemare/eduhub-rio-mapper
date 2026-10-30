# Read-only HoOpleiding lookup tests

These tests retrieve existing RIO opleidingseenheid `1001O3338` (Chemie), using
the production Raadplegen getter. They assume accreditation and do not check it.
They send exactly one `opvragen_opleidingseenheid` request, without resolving an
external key, loading OOAPI fixtures, uploading to ObjectStore or changing RIO.
The requesting institution OIN comes from client `rio-mapper-dev.jomco.nl` in
the clients-info file; the entity owner code `100B490` is not the sender OIN.

These namespaces run only in the dedicated suites; the normal unit and legacy
VCR suites keep their existing selection. Run the focused suites:

```sh
make record-ho-opleiding-v5
make playback-ho-opleiding-v5
make record-ho-opleiding-v6
make playback-ho-opleiding-v6
```

Record mode sets `VCR_RECORD=true` and requires these environment variables:

- `CLIENTS_INFO_PATH`: clients-info JSON containing `rio-mapper-dev.jomco.nl`
  and its institution OIN (see `test/test-clients.json`).
- `KEYSTORE`, `KEYSTORE_ALIAS`, `KEYSTORE_PASSWORD`: live RIO test certificate
  and private key in a single-entry JKS keystore.
- `RIO_READ_URL`: Raadplegen endpoint, normally
  `https://vt-webservice.duo.nl:6977/RIO/services/raadplegen4.0`.
- `RIO_RECIPIENT_OIN`: DUO recipient OIN, normally `00000001800866472000`.

No update URL, gateway, SURFconext, ObjectStore or Redis configuration is needed.
The RIO test environment must be available and reachable for recording.

Each recording is saved under
`test-v5/fixtures/ho-opleiding-lookup/1-chemie/` or the v6 equivalent.
These locations are outside the legacy `fixtures/vcr` directories, so neither
legacy record targets nor these new targets erase each other's cassettes or ID
mappings. Re-recording overwrites only the single dedicated lookup cassette.
Review and commit the real recordings after a successful live run. The existing
VCR recorder retains method, URL, SOAP body and selected headers, and excludes
keystore objects, private keys, passwords and HTTP authentication headers. SOAP
bodies include public certificates and signatures as in existing recordings.

Playback explicitly sets `VCR_RECORD=false`, ignores live environment settings,
and uses the repository test keystore and test clients-info. Its fixed public
RIO URL and recipient OIN match the normal recording configuration above.
It blocks the HTTP transport and fails if a cassette is missing; it never falls
back to a live request. Dependency downloads may require network access on the
first Clojure invocation; the tests themselves need none. Recording against a
different RIO endpoint or institution requires updating playback settings to
match that reviewed recording.

Assertions inspect the returned HoOpleiding, including its code, Chemie names,
entity start, absent entity end, both period starts, later Chemistry name, and
absence of an external key. Request checks also verify the SOAP body code and
sender identity, since the shared VCR matcher checks only URL/method/SOAPAction.
