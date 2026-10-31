# LubeLink — network calls

Every call LubeLink makes, to your LubeLogger server and to the optional map and station services. Nothing else is
contacted. LubeLogger's API is authoritative: LubeLink only consumes it (OD-11, see `docs/DECISIONS.md`).

LubeLink is tested against **LubeLogger 1.7.3 and 1.7.4**. `tests/contract/` holds the answers of a disposable
LubeLogger container with synthetic data, one folder per version, and the test suite replays them through the app's
own clients. `scripts/capture-contract.sh` records a new version's answers.

## Transport and authentication (all LubeLogger calls)

- **Server address:** HTTPS only, as a bare origin (no path, query, fragment or user info). Plain HTTP is refused.
  Certificates follow Android's normal trust rules, so self-signed and user-installed certificates are not trusted
  (OD-27).
- **Key:** header `x-api-key: <key>` on every call, never in a URL. Keys are stored encrypted with an Android Keystore
  key. Reads and record changes use the Editor key; deletes and their checks use the separate, optional Manager key.
- **Header `culture-invariant: true`** on every JSON call, so numbers, dates (`yyyy-MM-dd`) and booleans arrive in
  one form whatever the server's culture.
- **Redirects** are never followed; a 3xx is a failure.
- **Timeouts:** connect 10 s and read 20 s on every call (attachments: read 30 s).
- **Size limits:** a JSON response over 5 MiB is refused (the `/all` record lists: 32 MiB; write answers: 500 KB).
  Images may be up to 8 MiB and attachments up to 24 MiB.
- **Allowed paths:** each client checks its path against a fixed list before sending: the read client sends only the
  GETs below, the Editor client only the GET, POST and PUT paths, the Manager client only the GET and DELETE paths.

### How a response is read

Every field marked *required* below must be present and of the right type. If one is not, the screen says what could
not be read and names the field, for example *LubeLink could not read the vehicle list from LubeLogger ("year" is
missing or not valid)*. A missing value is never shown as 0, blank or "no". Unknown fields are ignored. JSON `null`
in an optional text field reads as empty. A list entry that is not an object fails the whole response. Server IDs
(`id`, `vehicleId`) are used only as IDs, never derived from names.

### Failure messages

| State | Recognised by | What the app says |
|---|---|---|
| Cannot find the server | Unknown host | "Cannot find the server. Check DNS or VPN." |
| Cannot reach the server | Connection refused or no route | "Cannot reach the server. Check the address, your network or VPN." |
| Timed out | Socket timeout | "Server timed out. Try again." |
| Certificate problem | TLS failure | "Secure connection failed. Check the HTTPS certificate." |
| Key not accepted | HTTP 401 | "LubeLogger did not accept the <API/Editor/Manager> key (HTTP 401). Check the key in LubeLogger, or disconnect and connect again." |
| Key lacks permission | HTTP 403 | "The <key> does not have permission for this in LubeLogger (HTTP 403)." |
| Unknown request | HTTP 404 | "LubeLogger did not recognise this request (HTTP 404). Check the server address. …" |
| Server fault | HTTP 5xx | "LubeLogger had a problem on the server (HTTP <n>). Try again later." |
| Not LubeLogger | A 2xx that is not JSON (for example a sign-in page in front of LubeLogger) | "The server did not answer the way LubeLogger does. Check the server address, and that no sign-in page sits in front of LubeLogger." |
| Malformed | Unparseable JSON, or a required field missing or of the wrong type | "LubeLogger sent a response LubeLink could not read…", or the named-field message above. |

### Writes: confirmed, never repeated

- LubeLogger offers no idempotency key (OD-03), so every POST, PUT and DELETE is sent **once**, in fixed-length
  streaming mode so the HTTP stack cannot replay it. A lost reply, or HTTP 408, 429 or 5xx, makes the result
  **uncertain**: the app asks you to check the record in LubeLogger, and holds new writes until you have.
- An add or edit is shown as done only after the record is read back by ID and matches what was sent. A delete is
  shown as done only after a read-back finds the record gone.
- Before every write the app re-reads the vehicle list and refuses the write if the target vehicle changed.
- An edit re-reads the record first and refuses if anything editable changed since the form opened. A Save with no
  change sends nothing ("No changes to save.").
- Delete is offered only once a Manager key is saved; a 403 refusal is shown as such.

## LubeLogger calls

Clients: R = read client `LubeLoggerApi`, E = `LubeLoggerEditorApi`, M = `LubeLoggerManagerApi`. Fields are
*required* unless marked otherwise.

### Reads

| Call | Client · used by | Fields used |
|---|---|---|
| `GET /api/whoami` | R connection check · E key check · M same-account check | `username` (text). The Manager key must belong to the same user as the Editor key (compared ignoring case). |
| `GET /api/vehicles` | R Garage · E, M vehicle check before every write | `id`, `year` (number); `make`, `model`, `imageLocation` (text); `useHours`, `isElectric` (boolean). Vehicle details, shown read-only in the vehicle page's ⓘ dialog: `licensePlate` (text), `isDiesel` (boolean), `purchaseDate`, `soldDate` (`yyyy-MM-dd`, or null or empty text when not set), `purchasePrice`, `soldPrice` (number, 0 when not set), `tags` (list of text). An unreadable detail makes only that vehicle's details "could not be read"; the Garage still loads. |
| `GET /api/vehicle/info` | R Garage | `vehicleData.id` (number); `lastReportedOdometer` (number or null); `urgentReminderCount`, `veryUrgentReminderCount`, `pastDueReminderCount`, `serviceRecordCount` (number) |
| `GET /api/vehicle/reminders/all` | R Garage, Reminders, background reminder check | `id`, `vehicleId` (number); `description`, `urgency`, `metric`, `userMetric`, `dueDate`, `notes` (text); `dueOdometer`, `dueDays`, `dueDistance` (number or null); `isRecurring` (optional boolean) |
| `GET /api/extrafields` | R Settings → Record Fields | per group: `recordType` (text), `extraFields` (list); per field: `name`, `fieldType`, `isRequired` |
| `GET /api/vehicle/{servicerecords,repairrecords,upgraderecords}?vehicleId=N` | R History | `id`, `vehicleId` (number, must equal `N`); `date`, `description`, `notes`, `tags` (text); `odometer` (number or null); `cost` (number); `files` (list of `name`, `location`) |
| `GET /api/vehicle/gasrecords?vehicleId=N&useMPG=true&useUKMPG=false` | R History | as above without `description`, plus `fuelConsumed`, `fuelEconomy` (number or null) and `extraFields` (list of `name` text and `value` text or null). The station name and address are the extra fields named `LubeLink Gas Station` and `LubeLink Station Address`. |
| `GET /api/vehicle/<kind>/all` (fuel adds `?useMPG=true&useUKMPG=false`) | R Calendar, Reports | as the per-vehicle rows, across every vehicle the key can see |
| `GET /api/vehicle/<kind>?vehicleId=N&id=R` (fuel adds `&useMPG=true&useUKMPG=false`) | E edit and read-back · M delete checks | everything Edit shows or sends back: `date`, `notes`, `tags`, `odometer`, `cost`, `description` (non-fuel); fuel: `fuelConsumed`, `startingSoc`, `endingSoc`, `isFillToFull`, `missedFuelUp`; `files`, `extraFields`. LubeLogger answers a list; exactly one match is found, none is missing, more than one is refused. |
| `GET <imageLocation>` (`/images/…` or `/defaults/…`) | R Garage card | an `image/*` body. LubeLogger has no thumbnail call, so each image is downloaded once at full size, scaled on the phone and cached. |
| `GET /documents/…` or `/images/…` (a record's `files[].location`) | R record details | the attachment's body, at most 24 MiB. Any other location, or one with a query or path traversal, is refused and the app suggests opening it in LubeLogger. |

### Writes

| Call | Client | Body and answer |
|---|---|---|
| `POST /api/vehicle/<kind>/add?vehicleId=N` | E add | `Date` (`yyyy-MM-dd`), `Odometer`, `Cost` (JSON number), `Notes`, `Tags`, plus `Description` (non-fuel) or `FuelConsumed` (JSON number), `IsFillToFull`, `MissedFuelUp` and `ExtraFields` (fuel). The answer must be `success: true` with a `recordId`. Decimals go as JSON numbers, which LubeLogger reads the same in every culture. |
| `PUT /api/vehicle/<kind>/update` | E edit | `id`, `vehicleId`, `date`, `odometer`, `cost`, `notes`, `tags`, and `files` and `extraFields` exactly as read, plus `description` or the fuel fields as read. LubeLogger replaces `files` and `extraFields` with what is sent, so they are never left out. |
| `DELETE /api/vehicle/<kind>/delete?id=R` | M delete | no body. A 400 or 404 answer is checked with a GET before it is reported. |

LubeLink never creates vehicles or reminders; those are managed in LubeLogger.

## Third-party calls

All optional, started by you, and none carries LubeLogger data.

| Call | Used by | Request | Timeouts / limits |
|---|---|---|---|
| Overpass `https://overpass-api.de/api/interpreter`, then `https://overpass.private.coffee/api/interpreter` if the first fails | Gas station picker, without a Geoapify key (or when Geoapify fails) | gas stations within 3.5 km of your position (rounded to about 100 m) or in the map's visible area; header `User-Agent: LubeLink-Android/<version> (user-initiated fuel station lookup)` | connect 7 s, read 17 s; 600 KB |
| Geoapify Places `https://api.geoapify.com/v2/places?categories=service.vehicle.fuel&…&apiKey=<key>` | Gas station picker, with your own Geoapify key | as above. Geoapify accepts the key only as a URL parameter; it is never logged or shown. | connect 7 s, read 12 s; 1 MB |
| Geoapify reverse geocode `https://api.geoapify.com/v1/geocode/reverse?…&apiKey=<key>` | "Suggest address" for a picked station | the station's coordinates | connect 7 s, read 12 s; 80 KB |
| Geoapify key test (the Places call at a fixed public point, `limit=1`) | Settings → Geoapify key | does not use your location | as Places |
| OpenFreeMap tiles `https://tiles.openfreemap.org/styles/liberty` | Station map | tiles for the area being viewed | MapLibre's own |
| Android `Geocoder` | "Use my location" for a station address | on the device, or the phone's geocoding service | 5 s location fix |

## Pages opened in the browser

These are not API calls: LubeLink opens pages on your own server in the phone's browser, which signs in on its own.

| Page | Opened from |
|---|---|
| `<server>/` | Reminders → "Manage reminders in LubeLogger"; the Garage's "+" tile (OD-38) |
| `<server>/Home?tab=settings` | Settings → LubeLogger settings |

## Background work

- No polling while the app is open. Data loads when a screen opens, on pull-to-refresh and on Refresh.
- With reminder alerts on, one WorkManager job runs every 24 hours with a network connection and sends the three
  Garage reads (`/api/vehicles`, `/api/vehicle/info`, `/api/vehicle/reminders/all`). "Check now" in Settings,
  switching alerts on and granting the notification permission each run the same check once (OD-28).
- A check that fails with a server or connection error is retried at most 3 times (30 s, 60 s, 120 s); a rejected key
  or unreadable data waits for the next daily check (OD-32). The reason is shown in Settings.
