# Nuxeo Unique Serial Number

This Apache-2.0 add-on defines an `Asset` document type extending `File`. A numbered Asset carries **one identity in two public fields**. Consumers can choose the string or the 64-bit integer. The server assigns both in the same document write.

## Release status

The `2023` branch targets Nuxeo LTS 2023.54 with Nuxeo Web UI (Polymer). The Marketplace package’s production state is **testing**. For Nuxeo LTS 2025 and the Maretha Angular Web UI layouts, use the [2025 branch](https://github.com/maretha-labs/nuxeo-unique-serial-number/tree/2025).

## Numbering an Asset

Select **Create USN** and a date in the create, edit, or typed import layout. The server handles the request at save time. The first successful create response contains the pair when the caller requests the `usn` schema. An unnumbered Asset can store a date alone. An ordinary edit keeps it unnumbered. Checking **Create USN** later assigns a number for that same date.

For REST, send the following document to `POST /nuxeo/api/v1/id/{parentId}` with `Content-Type: application/json` and `X-NXDocumentProperties: usn`.

```json
{
  "entity-type": "document",
  "type": "Asset",
  "name": "example",
  "properties": {
    "dc:title": "Example",
    "usn:usn_date": "2026-11-24T12:00:00Z",
    "usn:generate_usn": true
  }
}
```

The server clears the generation flag and stores the selected UTC day at noon. For example, a Java consumer can read either representation from the returned document.

```java
String label = (String) asset.getPropertyValue("usn:usn_string"); // "20261124100000"
long number = (Long) asset.getPropertyValue("usn:usn_long");      //  20261124100000
```

The default suffix range is 100000 through 150000, giving 50,001 possible assignments per UTC date. Abandoned reservations and failed saves can leave gaps. Assignments never wrap. Assigned fields and the exact stored date are immutable through the add-on’s ordinary write paths. Restoring an older version preserves the live identity, including an empty pair. Ordinary proxies and checked-in versions refer to their original owner. Expanding a numbered proxy into a live document can fail during create validation.

For clients that reserve before saving, `Asset.ReserveUsn` accepts `usnDate` and an optional `docId` and returns `usn_string`, `usn_long`, and `usn_date`. The reservation belongs to the authenticated user and can be consumed once, before it expires. Pass the target UUID for an existing Asset. A native import that supplies a USN requires a reservation bound to the imported UUID. Typed imports can request a new number on save.

## How numbering works

The add-on uses Nuxeo’s existing default key/value store. Each assignment increments one counter for the selected UTC date with `KeyValueStore.addAndGet`. It derives both fields from that single ordinal and saves them on the Asset together. Allocation needs neither a document search nor an external service call by the UI. Installing the matching package is sufficient on a Nuxeo server with a durable platform KV store. Cluster members must share that store.

The default range starts at 100000 and ends at 150000. An installation can select a different six-digit range using `maretha.asset.usn.minSuffix` and `maretha.asset.usn.maxSuffix` before first use. Retain the same bounds and store on every node and across upgrades. Protect the daily counters with the repository backups. Restoring older counter values can reissue numbers.

### Upgrading a numbered installation

Stop all old writers before switching versions. Keep the original `maretha.asset.usn.kvStoreName` and suffix bounds. For an earlier installation that used the implicit `usnStore`, explicitly set `maretha.asset.usn.kvStoreName=usnStore`. An existing generation setting also selects that legacy default. The allocator continues the existing `usn:seq:<yyyyMMdd>` counter, including ordinals consumed by abandoned assignments. If the original store or its history is missing, keep numbering offline. Document values alone cannot establish which numbers were previously issued. Finish outstanding reservations before upgrading or request new ones afterward. Receipts without an explicit expiration are rejected.

## Media and search

A main-file change classifies the Asset as Picture, Video, Audio, or ordinary File. The add-on applies the facets synchronously, clears stale media metadata, and schedules preview and video work after commit. Workers check the current source generation before publishing. Replacing A with B and then A again creates three distinct generations. A user with write permission can run `Asset.RecomputeMedia` to repair an individual live Asset. Picture metadata uses an Asset-specific rule. Video processing uses the server’s configured conversions.

The Asset stores its USN fields as ordinary Nuxeo document properties. They remain available through the document API. The add-on omits its USN and internal media-generation properties from outgoing search documents. Installation requires no index setup or reindex. Previously indexed documents retain their existing search representation until the platform rewrites them. A repository configured to extract all string metadata into its own full-text index, such as PostgreSQL VCS, can still include the string USN there. The add-on leaves that repository policy to the host.

## Build and install

Build this branch with Java 17 and Maven from the repository root.

```sh
mvn clean package
```

The build targets Nuxeo 2023.54.20 and writes the Marketplace ZIP under `nuxeo-unique-serial-number-package/target/2023.54.20/`. Install it with `nuxeoctl mp-install <path-to-zip>` on a stopped server with the matching Nuxeo Web UI package. The [UI module](nuxeo-unique-serial-number-web-ui/README.md) contains the Asset layouts.

The Maven test suite uses Nuxeo’s FeaturesRunner, including HTTP document creation. Native media tests use ImageMagick and FFmpeg.

## Implementation note

**A folder copy can expose a duplicate USN.** On MongoDB-backed Nuxeo 2023.54.20 and 2025.26.16, when a folder contains a numbered Asset, Nuxeo first writes the copied child with the source Asset’s USN. Another session can read that duplicate before the add-on clears both fields. A server crash before cleanup can leave the duplicate on the copy.
