# Decisions

The product decisions behind LubeLink's behaviour. Code cites them as `OD-nn`; numbers not listed here are
not about how the app behaves.

## Product decisions

| ID | Decision |
|---|---|
| OD-01 | An open Add or Edit form survives process death: the form session, vehicle, record type and, for an Edit, the record and a fingerprint of the server record as it was when the form opened. A restored Edit compares that fingerprint with a fresh load and refuses to save if LubeLogger changed meanwhile. |
| OD-02 | Application ID `com.kramttocs.lubelink` (since OD-79). Debug builds add `.review` and are labelled "LubeLink Review", so they install beside the release app. Builds under earlier IDs are separate apps. |
| OD-03 | LubeLogger has no idempotency-key contract, so an uncertain write is never retried automatically: a durable write lock stays until the user has checked LubeLogger. |
| OD-04 | Messages use plain language and name the vehicle, record type and date, not server record numbers. |
| OD-07 | Deleting a record is kept, with a separate Manager API key, a preflight check, a confirmation and a check that the record is gone. LubeLogger owns record history and the delete itself (exception X-01). |
| OD-09, OD-14 | CI runs on pushes to `main` and tags and on manual runs; Markdown/`docs/`-only pushes start no run; the emulator runs on tags and manual runs. Every revision released is run with the emulator. |
| OD-11 | LubeLink only consumes the LubeLogger API, which stays authoritative. Where a rule conflicts with how that API works, the API wins and the conflict is recorded as an exception. |
| OD-12 | Notes stop at 5,000 characters while typing (the Save limit); a longer paste is cut at 5,000 and a counter shows from 4,500. Notes already longer in LubeLogger are read-only in Edit, with a preview and a note to edit them in LubeLogger, and are kept unchanged. A Copy leaves them out and says so. Record and reminder details show the first 5,000 characters / 200 lines. |
| OD-12a | Adding a record from a reminder whose Notes are longer than 5,000 characters starts with empty Notes and a notice; the reminder is unchanged. |
| OD-13 | Alert-setting changes (excluding or hiding a vehicle, threshold, turning alerts off, Disconnect) take effect at once and never wait for a reminder check that is posting. If one lands during Android's post, that notification may appear for a moment and is then withdrawn; it is not counted as alerted. |
| OD-24 | When the vehicle list loads but the summaries or reminders do not, the Garage still shows the vehicles, with a notice that reminders or counts could not be loaded and a Retry. |
| OD-27 | LubeLink accepts only server certificates that Android trusts by default; user-installed and self-signed certificates are refused. |
| OD-28 | Background data budget: at most one reminder check every 24 hours, only with a network connection and only while alerts are on; three requests per check, plus one check per "Check now"; no polling while the app is open. |
| OD-31 | When one record type (Service, Repair, Upgrade or Fuel) fails to load, History and Calendar show the types that did load, with a notice naming the missing type and a Retry. Reports shows no totals until every type has loaded, so a total is never silently incomplete. |
| OD-32 | A failed background reminder check is retried at most 3 times, then waits for the next daily check. Malformed data and a rejected key are not retried. The checks run when alerts are turned on and when notification permission is granted count toward the budget. |
| OD-37 | Fuel figures show at most two decimals ("29.4 mpg · 13.2 gal"); History and record details show dates like Reminders ("Oct 20, 2026"); a vehicle's Recent history says "Loading…" and "No records yet". |
| OD-38 | The Garage ends with an "add vehicle" tile in LubeLogger's style (dashed border, green "+"). It opens the server's LubeLogger page in the browser, where the vehicle is added; LubeLink creates no vehicles. |
| OD-42 | Every network call a screen makes (key checks, station lookups, attachment downloads) goes through an interface or state holder, so failure, cancellation and late replies are tested. No visible change. |
| OD-45 | The favourite-description star sits inside the Description field, beside the dropdown arrow, so "Description" stays whole at every text size. |
| OD-49 | A selected vehicle's current odometer shows under its name in the History, Reminders and Calendar headers; History shows one panel when reminders are due, with a link to Reminders; the vehicle page's ⓘ opens the vehicle's details as LubeLogger has them (read only). |
| OD-50 | Every text field in the Add/Edit form and in the favourite-station dialog spans the full width, so its edges line up with the other fields. |
| OD-51 | The limitations in `docs/KNOWN-ISSUES.md` are accepted as listed. |
| OD-52 | History shows a header above each month with its total and record count (no total while a record type it shows did not load). Reminders suggests common reminders to set up in LubeLogger for a vehicle with none (LubeLink creates none). Reports shows four tiles (cost per mile, cost per day, distance, distance a day, from the first and last odometer readings in the period, in hours for an hourly vehicle) and, for one vehicle, a chart of its odometer readings; a tap names a reading. |
| OD-55 | With All vehicles, Reports' distance, distance a day and cost per mile add only vehicles counted in miles, with a line saying hourly vehicles are left out; cost per day covers every vehicle. |
| OD-58 | A vehicle with no reminders gets common ones to set up in LubeLogger; an electric vehicle's list leaves out the oil change. |
| OD-59 | The reminder suggestions can be turned off in Settings → Maintenance notifications ("Suggest common reminders", on by default). |
| OD-61 | Reports' distance a day is over the selected period, like cost per day; when a later reading is lower than the first, the distance tiles say "Readings go down" instead of "Not enough readings". |
| OD-63 | Reports' distance tiles say "Readings go down" when any reading in the period is lower than an earlier one (not only the last below the first); such a vehicle adds no distance to All vehicles. |
| OD-64 | Releases are signed with LubeLink's own release key, kept outside every repository. The signed APK is attached to this repository's GitHub Releases; without the signing secrets, CI builds nothing signed. |
| OD-53 | Settings → Garage & personalization offers Rounded (the default: 6 dp buttons and fields, 9 dp cards, 12 dp dialogs) or Square corners. Only the corners change; the choice belongs to the phone. |
| OD-54 | On Android 13 and later, with themed icons on, the launcher icon is the LubeLink logo in one colour from the wallpaper, at the normal icon's size. |
| OD-66 | The launcher icon's logo has a shorter circuit tail, ending in a small ring beside the gear, and is drawn larger so it fills the icon while staying inside Android's safe zone, so no launcher shape cuts it. The logo inside the app keeps the full tail. |
| OD-67 | In every free-text field (notes, descriptions, gas station names and addresses, vehicle nicknames) the keyboard starts each sentence with a capital letter; the server address, API keys and searches are typed as they are. |
| OD-68 | Version 1.0.1 (version code 45). |
| OD-75 | Edit and Delete stay, with a stated limit: LubeLink re-reads a record and refuses if it changed since Edit opened, but LubeLogger's API cannot refuse a save itself, so a change made elsewhere in the split second before LubeLink's save is overwritten. |
| OD-76 | An odometer reading of 0 means "not recorded": it is left out of the odometer chart and distance figures. |
| OD-77 | Cost totals add the exact amounts and then round to cents, so a total can differ by a cent from the sum of the rounded rows shown. |
| OD-79 | The application ID and code package are `com.kramttocs.lubelink`. An install under the earlier ID is a different app to Android: uninstall it and set LubeLink up again (records stay in LubeLogger). |
| OD-80 | Version 1.0.2 (version code 46). |
| OD-81 | Android 11 or later. |

## Standing exceptions

| ID | Rule set aside | Reason | Scope |
|---|---|---|---|
| X-01 | No hard deletes of record history | LubeLogger owns history and implements delete (OD-07, OD-11) | Manager-key delete only |
| X-02 | Identify things by ID, not by name | LubeLogger addresses extra fields only by name | The two LubeLink station fields |
| X-03 | CI with emulator tests on every push | GitHub-hosted minutes (OD-09, OD-14) | See OD-09, OD-14 |

## Technical decisions

- HTTP 408, 429 and 5xx after a POST or PUT mean the outcome is uncertain; it is never retried
  automatically. POST and PUT bodies are fixed-length, so a broken keep-alive cannot replay them.
- A no-op Edit sends no PUT and starts no write lock ("No changes to save").
- Costs and fuel quantities are sent as JSON numbers: LubeLogger parses decimal *strings* in the server's
  culture (a de-DE server stored "45.50" as 4550) but numbers the same everywhere.
- New Fuel records clear station inputs LubeLogger could not store, and say so; existing records keep theirs.
- Local app state is excluded from Android backup and device transfer.
- Within one account, only the most recently started Garage, Calendar or History load may update the
  screen; a load for a vehicle or filter no longer shown never makes the current one stale.
- Real-server tests run against a disposable `hargata/lubelogger:v1.7.4` container with synthetic data,
  never a real server. The Gradle 8.13 wrapper is committed and its checksum validated in CI.
