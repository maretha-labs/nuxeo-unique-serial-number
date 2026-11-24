import {
	ChangeDetectionStrategy,
	Component,
	Input,
	computed,
	inject,
	signal,
	viewChild,
} from '@angular/core';
import {
	DOCUMENT_LAYOUT_GATEWAY,
	DOCUMENT_LAYOUT_HOST,
	SchemaLayoutComponent,
	WebUiMessagePipe,
	type DocumentLayoutContext,
	type LayoutDocument,
} from '@maretha/webui-sdk';
import { ASSET_EDIT_CORE_LAYOUT } from './asset.fields';
import { AssetSave } from './asset-save';
import { AssetUsnPanelComponent, type UsnDraftState } from './asset-usn-panel.component';

@Component({
	selector: 'custom-asset-edit-layout',
	imports: [SchemaLayoutComponent, AssetUsnPanelComponent, WebUiMessagePipe],
	template: `
		<div class="modal-header">
			<h2 class="modal-title h5">
				{{ 'custom.asset.edit.title' | webuiMessage: 'Edit Asset' }}
			</h2>
		</div>
		<div class="modal-body">
			<nx-schema-layout
				[layout]="coreLayout"
				[documentId]="documentId()"
				[noEdit]="save.pending() || !!layoutContext?.noEdit"
				mode="edit"
				[draftOnly]="true"
				(valueChanged)="onCoreChanged($event)"
			/>
			<custom-asset-usn-panel
				mode="edit"
				[document]="effectiveDocument()"
				[disabled]="save.pending() || !!layoutContext?.noEdit"
				(usnChange)="onUsnChanged($event)"
			/>
			@if (save.error()) {
				<p role="alert" class="text-danger mt-2">{{ save.error() }}</p>
			}
		</div>
		<div class="modal-footer">
			<button
				type="button"
				class="btn btn-secondary"
				[disabled]="save.pending()"
				(click)="save.close(host)"
			>
				{{ 'custom.asset.cancel' | webuiMessage: 'Cancel' }}
			</button>
			<button type="button" class="btn btn-primary" [disabled]="!canSubmit()" (click)="submit()">
				{{
					save.pending()
						? ('custom.asset.saving' | webuiMessage: 'Saving…')
						: ('custom.asset.save' | webuiMessage: 'Save')
				}}
			</button>
		</div>
	`,
	changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetEditLayoutComponent {
	private _layoutContext: DocumentLayoutContext | undefined;

	/** Dialog hosts assign inputs directly, so a setter keeps signal state in sync without ngOnChanges. */
	@Input()
	set layoutContext(value: DocumentLayoutContext | undefined) {
		this._layoutContext = value;
		this.contextDoc.set(value?.document ?? null);
		this.coreDirty.set(false);
		this.save.error.set('');
	}
	get layoutContext(): DocumentLayoutContext | undefined {
		return this._layoutContext;
	}

	readonly host = inject(DOCUMENT_LAYOUT_HOST, { optional: true });
	private readonly gateway = inject(DOCUMENT_LAYOUT_GATEWAY);
	private readonly schema = viewChild(SchemaLayoutComponent);
	readonly coreLayout = ASSET_EDIT_CORE_LAYOUT;

	private readonly contextDoc = signal<Readonly<Record<string, unknown>> | null>(null);
	private readonly coreValues = signal<Readonly<Record<string, unknown>>>({});
	private readonly coreDirty = signal(false);
	private readonly usnState = signal<UsnDraftState>({
		valid: true,
		dirty: false,
		properties: {},
	});
	readonly save = new AssetSave();

	readonly effectiveDocument = computed<Readonly<Record<string, unknown>> | null>(() => {
		const loaded = this.schema()?.document();
		if (loaded) return loaded as unknown as Readonly<Record<string, unknown>>;
		return this.contextDoc();
	});

	readonly canSubmit = computed(() => {
		const schema = this.schema();
		const usn = this.usnState();
		const hasChanges = this.coreDirty() || usn.dirty;
		return (
			!this.save.pending() &&
			!this.save.reloadRequired() &&
			!this.layoutContext?.noEdit &&
			!!schema?.ready() &&
			!schema.invalid() &&
			!schema.hasUploads() &&
			usn.valid &&
			hasChanges
		);
	});

	documentId(): string {
		return String(this.layoutContext?.document?.['uid'] ?? '');
	}

	onCoreChanged(values: Readonly<Record<string, unknown>>): void {
		this.coreValues.set(values);
		this.coreDirty.set(Boolean(this.schema()?.dirty()));
	}

	onUsnChanged(state: UsnDraftState): void {
		this.usnState.set(state);
	}

	async submit(): Promise<void> {
		if (!this.canSubmit()) return;
		const loaded = this.schema()?.document();
		const target: LayoutDocument | null =
			loaded ??
			(this.layoutContext?.document
				? (this.layoutContext.document as unknown as LayoutDocument)
				: null);
		if (!target) return;

		const schema = this.schema();
		if (!schema) return;
		const intent = this.usnState().properties;
		const payload: Record<string, unknown> = { ...intent };
		if (this.coreDirty()) {
			for (const [key, value] of Object.entries(this.coreValues())) {
				if (JSON.stringify(value) !== JSON.stringify(target.properties[key] ?? null))
					payload[key] = value;
			}
		}
		await this.save.run(schema, intent, () => this.gateway.update(target, payload), this.host);
	}
}
