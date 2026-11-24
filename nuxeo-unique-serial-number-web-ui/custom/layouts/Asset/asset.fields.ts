import type { DocumentLayout } from '@maretha/webui-sdk';

/** Static Asset fields editable in Create mode (USN fields are managed by AssetUsnPanelComponent). */
export const ASSET_CREATE_CORE_LAYOUT: DocumentLayout = {
	id: 'custom.layout.asset.create.core',
	documentType: 'Asset',
	fields: [
		{
			property: 'dc:title',
			label: 'Title',
			labelKey: 'label.dublincore.title',
			required: true,
			maxLength: 255,
		},
		{
			property: 'dc:description',
			label: 'Description',
			labelKey: 'label.dublincore.description',
			maxLength: 4000,
		},
		{
			property: 'file:content',
			label: 'Content',
			labelKey: 'file.content',
		},
		{
			property: 'dc:nature',
			label: 'Nature',
			labelKey: 'label.dublincore.nature',
			widget: 'directory',
			directoryName: 'nature',
		},
		{
			property: 'dc:subjects',
			label: 'Subjects',
			labelKey: 'label.dublincore.subjects',
			widget: 'directory',
			directoryName: 'l10nsubjects',
		},
		{
			property: 'dc:coverage',
			label: 'Coverage',
			labelKey: 'label.dublincore.coverage',
			widget: 'directory',
			directoryName: 'l10ncoverage',
		},
		{
			property: 'dc:expired',
			label: 'Expiration date',
			labelKey: 'label.dublincore.expire',
		},
	],
};

/** Static Asset fields editable in Edit mode (supports replacing file:content to switch media facets). */
export const ASSET_EDIT_CORE_LAYOUT: DocumentLayout = {
	...ASSET_CREATE_CORE_LAYOUT,
	id: 'custom.layout.asset.edit.core',
};

/** Static Asset metadata fields editable in Import mode (file:content is uploaded by the import host). */
export const ASSET_IMPORT_CORE_LAYOUT: DocumentLayout = {
	id: 'custom.layout.asset.import.core',
	documentType: 'Asset',
	fields: ASSET_CREATE_CORE_LAYOUT.fields.filter((field) => field.property !== 'file:content'),
};

/** Read-only descriptive fields displayed in the metadata layout alongside USN and facet cards. */
export const ASSET_METADATA_CORE_LAYOUT: DocumentLayout = {
	id: 'custom.layout.asset.metadata.core',
	documentType: 'Asset',
	fields: ASSET_IMPORT_CORE_LAYOUT.fields.map((field) => ({ ...field, readOnly: true })),
};
