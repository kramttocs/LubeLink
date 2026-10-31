# Known issues and limitations

Only the first platform limit below can change a LubeLogger record unexpectedly.

| Issue | Effect |
|---|---|
| Edit's "changed on the server" check is sensitive to the order of attachments and extra fields | A rare false "changed in LubeLogger" refusal; never a silent overwrite |
| Cold start decrypts the API key and clears the receipt cache on the main thread | Possibly a slightly slower first screen |
| Changing the Editor key resets local alerts and station favourites (a warning is shown) | Those settings need to be set again after a key change |

## Platform limits

- **Two edits at the same moment:** before saving an edit or a delete, LubeLink re-reads the record and refuses if it
  changed since you opened it. LubeLogger's API cannot refuse a save itself, so a change made in the browser or on
  another phone in the split second between that check and LubeLink's save is overwritten (OD-75).
- **Electric-vehicle charging** records cannot be added or edited: the LubeLogger API does not return the editable
  charging quantity. Edit them in LubeLogger.
- **Uncertain writes:** when a reply is lost, or LubeLogger answers 408, 429 or 5xx, the change is never retried
  automatically. Check the record in LubeLogger first.
- **Reminders:** a reminder counts as alerted if Android would still show it just after posting. If notifications are
  switched off in Android's settings after that instant, the reminder alerts again only once it becomes more urgent,
  or after LubeLink's alerts are switched off and on.
- **Settings** take effect at once; Android writes them to disk shortly after, as for every app. A crash in that
  instant can lose the last change.
