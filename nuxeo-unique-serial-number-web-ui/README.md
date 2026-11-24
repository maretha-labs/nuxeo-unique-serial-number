# Asset layouts

This module contains the Asset layouts for both UIs. Both forms send the generation flag and date with the Asset save and display the string and long returned by Nuxeo.

## Nuxeo Web UI

Install the LTS 2025 add-on Marketplace package on the matching Nuxeo server. Its web resource bundle registers the Polymer layouts and messages. The HTML files deploy at `/ui/document/asset/`. [Polymer layout sources](src/main/resources/web/nuxeo.war/ui/document/asset/)

## Maretha Angular Web UI on LTS 2025

1. Copy this module’s `custom/layouts/Asset/` directory, including its spec, to the same path in your Angular host checkout. [Angular layout sources](custom/layouts/Asset/)
2. The sample uses the host's default owner prefix, `custom`. If `custom/owner.json` declares a different prefix, replace `custom-asset-`, `custom.asset.` and `custom.layout.asset.` in the copied TypeScript with the corresponding owner-prefixed forms, including in the spec. For example, the owner `acme` uses `acme-asset-`, `acme.asset.` and `acme.layout.asset.`.
3. In the host's `custom/index.ts`, add the import below and add `provideAssetUsnLayouts(),` to the provider array returned by `provideCustomExtensions()`. The provider registers the layouts and English and French messages.

   ```typescript
   import { provideAssetUsnLayouts } from './layouts/Asset';
   ```

4. From the host checkout run `npm run verify` and `npm run package:nuxeo`. Install the resulting Angular package alongside this add-on's LTS 2025 package.

When updating an existing customization, merge local edits and keep one registration for `provideAssetUsnLayouts()`. The current view layouts use the host's authenticated media renderers. Older copies of `asset-facet-views.ts`, `asset-usn.service.ts` and `usn-reservation-panel.component.ts` can be removed from the host once any local changes have been reconciled.
