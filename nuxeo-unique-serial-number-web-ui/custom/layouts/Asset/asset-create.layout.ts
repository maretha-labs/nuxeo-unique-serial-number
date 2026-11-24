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
} from '@maretha/webui-sdk';
import { ASSET_CREATE_CORE_LAYOUT } from './asset.fields';
import { AssetSave } from './asset-save';
import { AssetUsnPanelComponent, type UsnDraftState } from './asset-usn-panel.component';

@Component({
	selector: 'custom-asset-create-layout',
	imports: [SchemaLayoutComponent, AssetUsnPanelComponent, WebUiMessagePipe],
	template: `
		<div class="modal-header">
			<h2 class="modal-title h5">
				{{ 'custom.asset.create.title' | webuiMessage: 'New Asset' }}
			</h2>
		</div>
		<div class="modal-body">
			<nx-schema-layout
				[layout]="coreLayout"
				[parentId]="parentId()"
				[name]="name()"
				mode="create"
				[draftOnly]="true"
				[noEdit]="save.pending()"
				(valueChanged)="onCoreChanged($event)"
			/>
			<custom-asset-usn-panel
				mode="create"
				[disabled]="save.pending()"
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
						: ('custom.asset.create' | webuiMessage: 'Create')
				}}
			</button>
		</div>
	`,
	changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetCreateLayoutComponent {
	/** Dialog hosts assign inputs directly, so this stays a decorator input. */
	@Input() layoutContext: DocumentLayoutContext | undefined;

	readonly host = inject(DOCUMENT_LAYOUT_HOST, { optional: true });
	private readonly gateway = inject(DOCUMENT_LAYOUT_GATEWAY);
	private readonly schema = viewChild(SchemaLayoutComponent);
	readonly coreLayout = ASSET_CREATE_CORE_LAYOUT;

	private readonly coreValues = signal<Readonly<Record<string, unknown>>>({});
	private readonly usnState = signal<UsnDraftState>({
		valid: true,
		dirty: false,
		properties: {},
	});
	readonly save = new AssetSave();

	readonly name = computed(() =>
		String(this.coreValues()['dc:title'] ?? '')
			.replace(/[/\\]/g, '-')
			.trim(),
	);

	readonly canSubmit = computed(() => {
		const schema = this.schema();
		const usn = this.usnState();
		return (
			!this.save.pending() &&
			!this.save.reloadRequired() &&
			!!this.parentId() &&
			!!this.name() &&
			!!schema?.ready() &&
			!schema.invalid() &&
			!schema.hasUploads() &&
			usn.valid
		);
	});

	parentId(): string {
		return String(this.layoutContext?.parentDocument?.['uid'] ?? '');
	}

	onCoreChanged(values: Readonly<Record<string, unknown>>): void {
		this.coreValues.set(values);
	}

	onUsnChanged(state: UsnDraftState): void {
		this.usnState.set(state);
	}

	async submit(): Promise<void> {
		if (!this.canSubmit()) return;
		const schema = this.schema();
		if (!schema) return;
		const intent = this.usnState().properties;
		const payload = { ...this.coreValues(), ...intent };
		await this.save.run(
			schema,
			intent,
			() => this.gateway.create(this.parentId(), 'Asset', this.name(), payload),
			this.host,
		);
	}
}
