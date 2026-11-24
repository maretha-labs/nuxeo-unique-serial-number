import {
	ChangeDetectionStrategy,
	Component,
	Input,
	effect,
	inject,
	signal,
	viewChild,
} from '@angular/core';
import {
	DOCUMENT_IMPORT_DRAFT,
	SchemaLayoutComponent,
	type DocumentLayoutContext,
} from '@maretha/webui-sdk';
import { ASSET_IMPORT_CORE_LAYOUT } from './asset.fields';
import { AssetUsnPanelComponent, type UsnDraftState } from './asset-usn-panel.component';

@Component({
	selector: 'custom-asset-import-layout',
	imports: [SchemaLayoutComponent, AssetUsnPanelComponent],
	template: `
		<nx-schema-layout
			[layout]="coreLayout"
			[parentId]="parentId()"
			mode="import"
			[draftOnly]="true"
			[initialValues]="draft.properties"
			(valueChanged)="onCoreChanged($event)"
		/>
		<custom-asset-usn-panel
			mode="import"
			[initialGenerateUsn]="initialGenerateUsn"
			[initialUsnDate]="initialUsnDate"
			(usnChange)="onUsnChanged($event)"
		/>
	`,
	changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetImportLayoutComponent {
	/** Dialog hosts assign inputs directly, so this stays a decorator input. */
	@Input() layoutContext: DocumentLayoutContext | undefined;

	readonly draft = inject(DOCUMENT_IMPORT_DRAFT);
	readonly coreLayout = ASSET_IMPORT_CORE_LAYOUT;
	private readonly schema = viewChild(SchemaLayoutComponent);

	readonly initialGenerateUsn = Boolean(this.draft.properties['usn:generate_usn']);
	readonly initialUsnDate =
		typeof this.draft.properties['usn:usn_date'] === 'string'
			? this.draft.properties['usn:usn_date']
			: null;

	private readonly coreValues = signal<Readonly<Record<string, unknown>>>(this.draft.properties);
	private readonly usnState = signal<UsnDraftState>({
		valid: true,
		dirty: false,
		properties: {},
	});

	constructor() {
		effect(() => {
			const schema = this.schema();
			const usn = this.usnState();
			this.draft.setValid(!!schema?.ready() && !schema.invalid() && usn.valid);
		});
	}

	parentId(): string {
		return String(this.layoutContext?.parentDocument?.['uid'] ?? '');
	}

	onCoreChanged(values: Readonly<Record<string, unknown>>): void {
		this.coreValues.set(values);
		this.pushDraft();
	}

	onUsnChanged(state: UsnDraftState): void {
		this.usnState.set(state);
		this.pushDraft();
	}

	private pushDraft(): void {
		this.draft.update({
			...this.coreValues(),
			...this.usnState().properties,
		});
	}
}
