# SURFeduhub RIO mapper API

## Standen en hoofdobjecten

Updates van gegevens van een instituut worden altijd als "stand"
doorgegeven aan RIO. Een stand wordt altijd opgemaakt op basis van een
hoofdobject.  In deze koppeling worden de volgende typen objecten als
hoofdobject aangemerkt:

- EducationSpecification (RIO OpleidingEenheid)

- Education (Program of Course) (RIO AangebodenOpleiding)

Bij updates van een *Course* of *Program* wordt dat object als
hoofdobject genomen. Bij updates aan onderliggende objecten wordt het
"direct gelinkte" *Course* of *Program* als hoofdobject van een update
genomen.  Bijvoorbeeld: als een *ProgramOffering* aangepast wordt, is
het bijbehorende *Program* het hoofdobject.

Bij een update van een *Program*, worden geen updates aan de
gekoppelde *Programs* of gerelateerde *Courses* doorgegeven.  Bij een
update aan een *Course* worden geen updates aan de gerelateerde
*Programs* doorgegeven.  Voor deze situaties moeten aparte updates
aangeboden worden.

Alleen voor *Programs* en *Courses* welke gekoppeld zijn aan een
*EducationSpecification* kunnen updates aangeboden worden.

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

Na het aanmaken van een job wordt een *token* teruggeven waarmee de
status van die job gevraagd kan worden.  Alle job endpoints reageren
in de onderstaande vorm:

```
200 OK
Content-Type: application/json

{"token": "00112233-4455-6677-8899-aabbccddeeff"}
```

waar `00112233-4455-6677-8899-aabbccddeeff` het token is dat gebruikt
kan worden om de status van de job uit te vragen met het state endpoint.  Dat wordt uitvraagt met:

### Status

```
GET /status/00112233-4455-6677-8899-aabbccddeeff
```

er reageert met bijvoorbeeld:

```
200 OK
Content-Type: application/json

{
  "status": "pending",
  "token": "00112233-4455-6677-8899-aabbccddeeff",
  "resource": "courses/ffeeddcc-bbaa-0099-8877-665544332211"
}
```

Waar status `status` de status van de job is, `token` het token
waarmee de status opgevraagd werd, en `resource` het OOAPI object gemoeid met deze job.

Als het token niet herkend wordt:

```
404 Not Found
Content-Type: application/json

{"status": "unknown"}
```

De `status` eigenschap kan één van de volgende waarden hebben:

- `unknown`

  De job is onbekend.

- `pending`

  De job staat in de wachtrij.
  
- `in-progress`

  De job wordt uitgevoerd.

- `done`

  De job is uitgevoerd.  Eventueel bevat het antwoordt extra gegevens
  zoals `opleidingseenheidcode`, dit is afhankelijk van het type job.
  
- `time-out`

  De job is mislukt na herhaaldelijke poging op of het OOAPI of DUO
  endpoint dat hiermee gemoeid is.
  
- `error`

  De job is mislukt omdat de gegevens niet compatibel zijn of
  geaccepteerd worden door DUO.

Een *error* heeft als extra eigenschap `phase` welke aangeeft in welke
fase de job mislukt en `message` met de foutmelding.

Als de in `X-Callback` een URL van een webhook meegegeven bij het aanmaken van de job, wordt hier een `POST` op uitgevoerd zodra de job status `done`, `error` of `time-out` heeft bereikt.  Hierbij wordt de *status* meegegeven.  Als de webhook niet met een HTTP succes status antwoordt of niet bereikbaar is (bijvoorbeeld bij een verbindingsfout), wordt het na 30 seconden nog een keer geprobeerd.  Er worden maximaal 4 pogingen gedaan per job (de eerste poging plus 3 herhalingen).

### Upsert

Aanpassingen en toevoegingen van *EducationSpecification* objecten
worden doorgegeven via:

```
POST /job/upsert/education-specifications/ffeeddcc-bbaa-0099-8877-665544332211
```

Als deze job succesvol afgerond is, wordt bij de status de volgende
extra informatie meegegeven:

```json
{
  "status": "done",
  "attributes": { "opleidingseenheidcode": "1234O5678" }
}
```

Aanpassingen en toevoegingen van, respectievelijk, *Program* en
*Course* objecten worden doorgegeven via:

```
POST /job/upsert/programs/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/upsert/courses/ffeeddcc-bbaa-0099-8877-665544332211
```

Als deze job succesvol afgerond is, wordt bij de status de volgende
extra informatie meegegeven:

```json
{
  "status": "done",
  "attributes": { "aangebodenopleidingcode": "TODO" }
}
```

### Delete

Verwijderingen van, respectievelijk, *EducationSpecification*,
*Program* en *Course* objecten worden doorgegeven via:

```
POST /job/delete/education-specifications/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/delete/programs/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/delete/courses/ffeeddcc-bbaa-0099-8877-665544332211
```

Er wordt bij het verwijderen geen extra informatie bij de status
opgenomen.

### Link

Via de link-call wordt de eigen opleidingssleutel aangepast van het object 
met het gegeven type en rio-code. 

```
POST /job/link/1234O4321/education-specifications/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/link/ffeeddcc-bbaa-0099-8877-665544332211/programs/ffeeddcc-bbaa-0099-8877-665544332212
POST /job/link/ffeeddcc-bbaa-0099-8877-665544332222/courses/ffeeddcc-bbaa-0099-8877-665544332222
```

Bij de status wordt doorgegeven of de call geslaagd is, en wat de oorspronkelijke sleutel was.

Merk op dat de sleutel van een education-specification een rio-code
(onderwijseenheidcode) is maar dat die van courses en programs OOAPI
UUID's zijn!

```json
{
  "status": "done",
  "token": "00112233-4455-6677-8899-aabbccddeeff",
  "resource": "education-specifications/ffeeddcc-bbaa-0099-8877-665544332211",
  "attributes": {
    "eigenOpleidingseenheidSleutel": {
      "diff": true,
      "old-id": "<id-old>",
      "new-id": "<id>"
    }
  }
}
```

### Unlink

Via de unlink-call wordt de eigen opleidingssleutel verwijderd van een object met het gegeven rio-code en type.

```
POST /job/unlink/1234O4321/education-specifications
POST /job/unlink/ffeeddcc-bbaa-0099-8877-665544332211/programs
POST /job/unlink/ffeeddcc-bbaa-0099-8877-665544332212/courses
```

De status is hetzelfde als die van een link-call.  Zie ook de opmerking over de sleutels in [[Link]].

### Dry run upserts

Het doorvoeren van een aanpassing of toevoeging kan gesimuleerd worden
met een *dry run*.

```
POST /job/dry-run/upsert/education-specifications/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/dry-run/upsert/programs/ffeeddcc-bbaa-0099-8877-665544332211
POST /job/dry-run/upsert/courses/ffeeddcc-bbaa-0099-8877-665544332211
```

Als deze job succesvol afgerond is, wordt bij de status doorgegeven
welke gegevens aangepast zouden worden:

```json
{
  "status": "done",
  "token": "00112233-4455-6677-8899-aabbccddeeff",
  "resource": "education-specifications/ffeeddcc-bbaa-0099-8877-665544332211",
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

## Autorisatie

Om gebruik te kunnen maken van de API zijn *bearer tokens* nodig welke
verkrijgbaar zijn via de SURFconext.

## Debuggen

Als de server gestart is met `STORE_HTTP_REQUESTS` variabel gezet op
`true`, is het mogelijk in het *status object* HTTP verkeer tussen het
OOAPI en RIO endpoint gelogd te krijgen.  Om deze gegevens te krijgen
moet de query parameter `http-messages=true` doorgegeven worden bij
het aanmaken van een job en wordt het status object uitgebreid met
`http-messages`. De http-messages array bevat maps met "req" en "res" 
keys. Wanneer het een json response betreft, dan bevat "res" naast "body"
(de string-representatie van de json) ook een "json-body" met de json zelf.

```
POST /job/dry-run/upsert/courses/ffeeddcc-bbaa-0099-8877-665544332211?http-messages=true
```

```json
{
  "status": "done",
  "token": "00112233-4455-6677-8899-aabbccddeeff",
  "resource": "courses/ffeeddcc-bbaa-0099-8877-665544332211",
  "http-messages": [
    {
      "req": {
        "url": "https://example.com/course/ffeeddcc-bbaa-0099-8877-665544332211",
        "method": "GET",
        "params": null,
        "body": null
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
