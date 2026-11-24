import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import {
	provideDocumentLayoutExtensions,
	provideWebUiMessages,
	type DocumentLayoutContext,
} from '@maretha/webui-sdk';
import { AssetCreateLayoutComponent } from './asset-create.layout';
import { AssetEditLayoutComponent } from './asset-edit.layout';
import { AssetImportLayoutComponent } from './asset-import.layout';
import { ASSET_USN_MESSAGES } from './asset-messages';
import { AssetMetadataLayoutComponent } from './asset-metadata.layout';

function hasFacet(context: DocumentLayoutContext, facet: string): boolean {
	const facets = context.document?.['facets'];
	return Array.isArray(facets) && facets.includes(facet);
}

/**
 * Registers all Asset layouts (create, edit, import, metadata, and facet-driven view layouts)
 * and EN/FR labels for maretha-angular-webui (Nuxeo LTS 2025).
 */
export function provideAssetUsnLayouts(): EnvironmentProviders {
	return makeEnvironmentProviders([
		provideWebUiMessages('custom.asset.usn', ASSET_USN_MESSAGES),
		provideDocumentLayoutExtensions({
			id: 'custom.asset.usn.layouts',
			layouts: [
				{
					id: 'custom.asset.create',
					documentType: 'Asset',
					mode: 'create',
					priority: 10,
					component: AssetCreateLayoutComponent,
				},
				{
					id: 'custom.asset.edit',
					documentType: 'Asset',
					mode: 'edit',
					priority: 10,
					component: AssetEditLayoutComponent,
				},
				{
					id: 'custom.asset.import',
					documentType: 'Asset',
					mode: 'import',
					priority: 10,
					component: AssetImportLayoutComponent,
				},
				{
					id: 'custom.asset.metadata',
					documentType: 'Asset',
					mode: 'metadata',
					priority: 10,
					component: AssetMetadataLayoutComponent,
				},
				{
					// A componentless view uses the host's authenticated MIME renderer and file actions.
					id: 'custom.asset.view.picture',
					documentType: 'Asset',
					mode: 'view',
					priority: 20,
					when: (context) => hasFacet(context, 'Picture'),
				},
				{
					id: 'custom.asset.view.video',
					documentType: 'Asset',
					mode: 'view',
					priority: 20,
					when: (context) => hasFacet(context, 'Video'),
				},
				{
					id: 'custom.asset.view.audio',
					documentType: 'Asset',
					mode: 'view',
					priority: 20,
					when: (context) => hasFacet(context, 'Audio'),
				},
				{
					id: 'custom.asset.view.default',
					documentType: 'Asset',
					mode: 'view',
					priority: 10,
				},
			],
		}),
	]);
}
