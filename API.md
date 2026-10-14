# SURFeduhub RIO mapper API

## Versies

De mapper bestaat in twee versies, één voor OOAPI v5 en één voor OOAPI
v6.  De API van beide versies is grotendeels gelijk; de verschillen
zijn:

| | v5 | v6 |
|---|---|---|
| Resource types | `education-specifications`, `programs`, `courses` | `programmes`, `courses` |
| Antwoord bij aanmaken job | `200 OK` | `201 Created` |
| Soort `rio-code` bij link/unlink | bepaald door het type in de URL | bepaald door het formaat van de code |

In de voorbeelden hieronder staat met `# v5` of `# v6` aangegeven voor
welke versie een regel geldt.  Regels zonder aanduiding gelden voor
beide versies.

## Standen en hoofdobjecten

Updates van gegevens van een instituut worden altijd als "stand"
doorgegeven aan RIO. Een stand wordt altijd opgemaakt op basis van een
hoofdobject.  In deze koppeling worden de volgende typen objecten als
hoofdobject aangemerkt:

- Opleidingsspecificatie (RIO OpleidingEenheid)

  In v5 is dit een *EducationSpecification*.  In v6 bestaan er geen
  aparte education specifications; een *Programme* met
  `consumer.specificationType` (programmeType "specification") neemt
  die rol over.

- Opleiding (RIO AangebodenOpleiding)

  In v5 een *Program* of *Course*, in v6 een *Programme* (die geen
  specificatie is) of *Course*.

Bij updates van een *Course* of *Program(me)* wordt dat object als
hoofdobject genomen. Bij updates aan onderliggende objecten wordt het
"direct gelinkte" *Course* of *Program(me)* als hoofdobject van een
update genomen.  Bijvoorbeeld: als een *ProgramOffering* aangepast
wordt, is het bijbehorende *Program* het hoofdobject.

Bij een update van een *Program(me)*, worden geen updates aan de
gekoppelde *Program(me)s* of gerelateerde *Courses* doorgegeven.  Bij
een update aan een *Course* worden geen updates aan de gerelateerde
*Program(me)s* doorgegeven.  Voor deze situaties moeten aparte updates
aangeboden worden.

Alleen voor opleidingen welke gekoppeld zijn aan een
opleidingsspecificatie kunnen updates aangeboden worden.

## API endpoints

Operaties worden altijd asynchroon uitgevoerd, dat betekent dat er een
*job* in een wachtrij wordt gezet waarvan de *status* uitgevraagd kan
worden, eventueel wordt er een notificatie teruggestuurd via een
*webhook*.  Per instituut wordt een wachtrij bijgehouden waarvan maar
één job tegelijk uitgevoerd wordt om te garanderen dat jobs op
volgorde uitgevoerd worden.

```
POST /job/upsert/courses/ffeeddcc-bbaa-0099-8877-665544332211
X-Callback: https://example.com/ffeeddcc-bbaa-0099-8877-665544332211
```

Bij het aanmaken van een job kunnen de volgende optionele headers
meegegeven worden:

- `X-Callback`

  URL van een webhook (alleen `http` of `https`), zie
  [Webhook](#webhook).

- `X-Suppress-Http-Messages: true`

  Laat het gelogde HTTP verkeer (zie [Debuggen](#debuggen)) weg uit
  de webhook notificatie.

Na het aanmaken van een job wordt een *token* teruggeven waarmee de
status van die job gevraagd kan worden.  Alle job endpoints reageren
in de onderstaande vorm:

```
# v5
200 OK
# v6
201 Created

Content-Type: application/json

{"token": "00112233-4455-6677-8899-aabbccddeeff"}
```

waar `00112233-4455-6677-8899-aabbccddeeff` het token is dat gebruikt
kan worden om de status van de job uit te vragen met het status
endpoint.

Mogelijke foutmeldingen bij het aanmaken van een job:

- `400 Bad Request` met `Invalid UUID` als de OOAPI id geen geldige
  UUID is.
- `400 Bad Request` met `Invalid opleidingscode '...'` of `Invalid
  aangeboden opleidingcode '...'` als de rio-code van een link of
  unlink ongeldig is.
- `400 Bad Request` met `Malformed callback url` als de `X-Callback`
  header geen geldige http(s) URL bevat.
- `403 Forbidden` als de client niet aan een instituut gekoppeld is.
- `404 Not Found` bij een onbekend type of onbekende actie.  Een
  link zonder rio-code (zoals `POST /job/link/courses/...`) geeft
  ook `404 Not Found`; gebruik `/job/link/:rio-code/:type/:id`.
- `503 Service Unavailable` met `Server stopping` als de server aan
  het afsluiten is.

### Status

```
GET /status/00112233-4455-6677-8899-aabbccddeeff
```

er reageert met bijvoorbeeld:

```
200 OK
Content-Type: application/json

{
  "status": "done",
  "token": "00112233-4455-6677-8899-aabbccddeeff",
  "action": "upsert",
  "resource": "course/ffeeddcc-bbaa-0099-8877-665544332211",
  "created-at": "2024-01-01T12:00:00.000Z",
  "started-at": "2024-01-01T12:00:01.000Z",
  "finished-at": "2024-01-01T12:00:05.000Z"
}
```

Waar `status` de status van de job is, `token` het token waarmee de
status opgevraagd werd, `action` de actie van de job (`upsert`,
`delete`, `dry-run-upsert` of `link`) en `resource` het OOAPI object
gemoeid met deze job, in de vorm `<type>/<id>` met het type in
enkelvoud (`course`, `program`, `programme` of
`education-specification`).  `created-at` en `started-at` geven aan
wanneer de job aangemaakt en gestart is; `finished-at` wordt
toegevoegd zodra de job `done`, `error` of `time-out` is.

Statussen worden een week bewaard (instelbaar met `STATUS_TTL_SEC`).

Als het token niet herkend wordt (of de status verlopen is):

```
404 Not Found
Content-Type: application/json

{"status": "unknown"}
```

Als het token geen geldige UUID is wordt `400 Bad Request` met
`Invalid UUID` teruggegeven.

De `status` eigenschap kan één van de volgende waarden hebben:

- `unknown`

  De job is onbekend (alleen bij een `404 Not Found`).

- `pending`

  De job staat in de wachtrij.

- `in-progress`

  De job wordt uitgevoerd.

- `done`

  De job is uitgevoerd.  Eventueel bevat het antwoord extra gegevens
  onder `attributes`, dit is afhankelijk van het type job.

- `time-out`

  De job is mislukt na herhaaldelijke pogingen op het OOAPI of RIO
  endpoint dat hiermee gemoeid is.

- `error`

  De job is mislukt omdat de gegevens niet compatibel zijn of
  geaccepteerd worden door DUO.

Een *error* en een *time-out* hebben als extra eigenschappen `phase`,
welke aangeeft in welke fase de job mislukt is, en `message` met de
foutmelding.

### Webhook

Als in `X-Callback` een URL van een webhook meegegeven is bij het
aanmaken van de job, wordt hier een `POST` op uitgevoerd zodra de job
status `done`, `error` of `time-out` heeft bereikt.  Hierbij wordt de
*status* meegegeven (inclusief `http-messages`, tenzij de job
aangemaakt is met `X-Suppress-Http-Messages: true`).  Als de webhook
niet met een HTTP succes status antwoordt of niet bereikbaar is
(bijvoorbeeld bij een verbindingsfout), wordt het na 30 seconden
opnieuw geprobeerd, maximaal 3 keer.  Er worden dus maximaal 4
pogingen gedaan per job (de eerste poging plus 3 herhalingen).

### Upsert

Aanpassingen en toevoegingen van opleidingsspecificaties worden
doorgegeven via:

```
# v5
POST /job/upsert/education-specifications/ffeeddcc-bbaa-0099-8877-665544332211
# v6 (programme met een specificationType)
POST /job/upsert/programmes/ffeeddcc-bbaa-0099-8877-665544332211
```

Als deze job succesvol afgerond is, wordt bij de status de volgende
extra informatie meegegeven:

```json
{
  "status": "done",
  "attributes": { "opleidingseenheidcode": "1234O5678" }
}
```

Aanpassingen en toevoegingen van opleidingen worden doorgegeven via:

```
# v5
POST /job/upsert/programs/ffeeddcc-bbaa-0099-8877-665544332211
# v6
POST /job/upsert/programmes/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/upsert/courses/ffeeddcc-bbaa-0099-8877-665544332211
```

Als deze job succesvol afgerond is, wordt bij de status de
aangeboden-opleidingcode (een UUID) meegegeven:

```json
{
  "status": "done",
  "attributes": { "aangebodenopleidingcode": "ffeeddcc-bbaa-0099-8877-665544332211" }
}
```

### Delete

Verwijderingen van objecten worden doorgegeven via:

```
# v5
POST /job/delete/education-specifications/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/delete/programs/ffeeddcc-bbaa-0099-8877-665544332211
# v6
POST /job/delete/programmes/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/delete/courses/ffeeddcc-bbaa-0099-8877-665544332211
```

Er wordt bij het verwijderen geen extra informatie bij de status
opgenomen.

### Link

Via de link-call wordt de eigen sleutel van het RIO object met de
gegeven rio-code aangepast naar de gegeven OOAPI id.

```
# v5
POST /job/link/1234O4321/education-specifications/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/link/ffeeddcc-bbaa-0099-8877-665544332211/programs/ffeeddcc-bbaa-0099-8877-665544332212
# v6
POST /job/link/1234O4321/programmes/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/link/ffeeddcc-bbaa-0099-8877-665544332211/programmes/ffeeddcc-bbaa-0099-8877-665544332212
POST /job/link/ffeeddcc-bbaa-0099-8877-665544332222/courses/ffeeddcc-bbaa-0099-8877-665544332222
```

Bij de status wordt doorgegeven of de call geslaagd is, en wat de
oorspronkelijke sleutel was.

De rio-code is een opleidingseenheidcode (zoals `1234O4321`) of een
aangeboden-opleidingcode (een UUID).  In v5 bepaalt het type welke
soort code verwacht wordt: bij `education-specifications` is het een
opleidingseenheidcode, bij `programs` en `courses` een
aangeboden-opleidingcode.  In v6 bepaalt het formaat van de code de
soort: een code van de vorm `1234O4321` is een opleidingseenheidcode,
anders is het een aangeboden-opleidingcode.

```json
{
  "status": "done",
  "token": "00112233-4455-6677-8899-aabbccddeeff",
  "action": "link",
  "resource": "programme/ffeeddcc-bbaa-0099-8877-665544332211",
  "attributes": {
    "eigenOpleidingseenheidSleutel": {
      "diff": true,
      "old-id": "<id-old>",
      "new-id": "<id>"
    }
  }
}
```

Bij een aangeboden opleiding heet het attribuut
`eigenAangebodenOpleidingSleutel`.

### Unlink

Via de unlink-call wordt de eigen sleutel verwijderd van een object
met de gegeven rio-code en type.

```
# v5
POST /job/unlink/1234O4321/education-specifications
POST /job/unlink/ffeeddcc-bbaa-0099-8877-665544332211/programs
# v6
POST /job/unlink/1234O4321/programmes
POST /job/unlink/ffeeddcc-bbaa-0099-8877-665544332211/programmes
POST /job/unlink/ffeeddcc-bbaa-0099-8877-665544332212/courses
```

De status is hetzelfde als die van een link-call.  Zie ook de
opmerking over de rio-code bij [Link](#link).

### Dry run upserts

Het doorvoeren van een aanpassing of toevoeging kan gesimuleerd worden
met een *dry run*.

```
# v5
POST /job/dry-run/upsert/education-specifications/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/dry-run/upsert/programs/ffeeddcc-bbaa-0099-8877-665544332211
# v6
POST /job/dry-run/upsert/programmes/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/dry-run/upsert/courses/ffeeddcc-bbaa-0099-8877-665544332211
```

Als deze job succesvol afgerond is, wordt bij de status doorgegeven
welke gegevens aangepast zouden worden:

```json
{
  "status": "done",
  "token": "00112233-4455-6677-8899-aabbccddeeff",
  "action": "dry-run-upsert",
  "resource": "education-specification/ffeeddcc-bbaa-0099-8877-665544332211",
  "attributes": {
    "begindatum": {
      "diff": true,
      "current": "2023-01-01",
      "proposed": "2023-06-01"
    },
    "eigenOpleidingseenheidSleutel": {
      "diff": false
    },
    "omschrijving": {
      "diff": false
    },
    "naamLang": {
      "diff": false
    },
    "naamKort": {
      "diff": false
    },
    "internationaleNaam": {
      "diff": false
    },
    "status": "found"
  }
}
```

### Health

```
GET /health
```

Dit endpoint vereist geen autorisatie en antwoordt met `200 OK` en
`OK` als de wachtrij (Redis) bereikbaar is, en anders met `503
Service Unavailable`.

## Autorisatie

Om gebruik te kunnen maken van de API zijn *bearer tokens* nodig welke
verkrijgbaar zijn via de SURFconext.

## Debuggen

Als de server gestart is met `STORE_HTTP_REQUESTS` gezet op `true`
(de standaardwaarde), wordt het HTTP verkeer tussen de mapper en de
OOAPI en RIO endpoints van elke job bewaard in het *status object*.
Dit verkeer wordt alleen getoond als bij het **opvragen van de
status** de query parameter `http-messages=true` meegegeven wordt;
het status object wordt dan uitgebreid met `http-messages`.  Bij het
aanmaken van een job heeft deze parameter geen effect.

De http-messages array bevat maps met "req" (met `url`, `method`,
`params`, `body` en `headers`) en "res" (met `status` en `body`)
keys.  Als het request een `Accept: application/json` header had,
bevat "res" naast "body" (de string-representatie van de json) ook een
"json-body" met de json zelf.

```
GET /status/00112233-4455-6677-8899-aabbccddeeff?http-messages=true
```

```json
{
  "status": "done",
  "token": "00112233-4455-6677-8899-aabbccddeeff",
  "action": "dry-run-upsert",
  "resource": "course/ffeeddcc-bbaa-0099-8877-665544332211",
  "http-messages": [
    {
      "req": {
        "url": "https://example.com/courses/ffeeddcc-bbaa-0099-8877-665544332211",
        "method": "GET",
        "params": null,
        "body": null,
        "headers": {"Accept": "application/json; version=5"}
      },
      "res": {
        "status": 404,
        "body": "{\"course\":{...",
        "json-body": {"course":{}}
      }
    }, {
      "req": "...",
      "res": "..."
    }
  ]
}
```
