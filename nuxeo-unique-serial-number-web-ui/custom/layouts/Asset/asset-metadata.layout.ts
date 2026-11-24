import { ChangeDetectionStrategy, Component, Input, computed, inject, signal } from '@angular/core';
import {
	DOCUMENT_LAYOUT_HOST,
	SchemaLayoutComponent,
	WebUiMessagePipe,
	type DocumentLayoutContext,
} from '@maretha/webui-sdk';
import { ASSET_METADATA_CORE_LAYOUT } from './asset.fields';
import { AssetUsnPanelComponent } from './asset-usn-panel.component';

function asRecord(value: unknown): Readonly<Record<string, unknown>> {
	return value && typeof value === 'object' && !Array.isArray(value)
		? (value as Readonly<Record<string, unknown>>)
		: {};
}

function hasFacet(doc: Readonly<Record<string, unknown>> | null, facet: string): boolean {
	const facets = doc?.['facets'];
	return Array.isArray(facets) && facets.includes(facet);
}

@Component({
	selector: 'custom-asset-metadata-layout',
	imports: [SchemaLayoutComponent, AssetUsnPanelComponent, WebUiMessagePipe],
	template: `
		<custom-asset-usn-panel mode="readonly" [document]="docState()" />

		<nx-schema-layout [layout]="layout" [documentId]="documentId()" mode="metadata" />

		@if (isPicture()) {
			<section class="facet-card" data-testid="asset-metadata-picture">
				<h4>{{ 'custom.asset.picture.info' | webuiMessage: 'Picture Information' }}</h4>
				<dl>
					@if (pictureInfo()['width']) {
						<dt>{{ 'custom.asset.picture.dimensions' | webuiMessage: 'Dimensions' }}</dt>
						<dd>{{ pictureInfo()['width'] }} × {{ pictureInfo()['height'] }}</dd>
					}
					@if (pictureInfo()['format']) {
						<dt>{{ 'custom.asset.picture.format' | webuiMessage: 'Format' }}</dt>
						<dd>{{ pictureInfo()['format'] }}</dd>
					}
					@if (pictureInfo()['colorSpace']) {
						<dt>{{ 'custom.asset.picture.colorSpace' | webuiMessage: 'Color Space' }}</dt>
						<dd>{{ pictureInfo()['colorSpace'] }}</dd>
					}
				</dl>
			</section>
		}

		@if (isVideo()) {
			<section class="facet-card" data-testid="asset-metadata-video">
				<h4>{{ 'custom.asset.video.info' | webuiMessage: 'Video Information' }}</h4>
				<dl>
					@if (videoInfo()['width']) {
						<dt>{{ 'custom.asset.video.dimensions' | webuiMessage: 'Dimensions' }}</dt>
						<dd>{{ videoInfo()['width'] }} × {{ videoInfo()['height'] }}</dd>
					}
					@if (videoInfo()['duration'] !== null && videoInfo()['duration'] !== undefined) {
						<dt>{{ 'custom.asset.video.duration' | webuiMessage: 'Duration' }}</dt>
						<dd>{{ videoInfo()['duration'] }} s</dd>
					}
				</dl>
			</section>
		}

		@if (isAudio()) {
			<section class="facet-card" data-testid="asset-metadata-audio">
				<h4>{{ 'custom.asset.audio.info' | webuiMessage: 'Audio Information' }}</h4>
				<dl>
					@if (props()['aud:duration'] !== null && props()['aud:duration'] !== undefined) {
						<dt>{{ 'custom.asset.audio.duration' | webuiMessage: 'Duration' }}</dt>
						<dd>{{ props()['aud:duration'] }} s</dd>
					}
				</dl>
			</section>
		}

		@if (host) {
			<button type="button" class="btn btn-secondary mt-2" (click)="host.cancel()">
				{{ 'custom.asset.close' | webuiMessage: 'Close' }}
			</button>
		}
	`,
	styles: `
		.facet-card {
			margin-top: 12px;
			padding: 12px 16px;
			border: 1px solid var(--nuxeo-border, #d8d8dc);
			border-radius: 4px;
		}
		.facet-card h4 {
			margin: 0 0 8px;
			font-size: 0.92rem;
		}
		.facet-card dl {
			display: grid;
			grid-template-columns: auto 1fr;
			gap: 4px 12px;
			margin: 0;
		}
		.facet-card dd {
			margin: 0;
		}
	`,
	changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetMetadataLayoutComponent {
	private _layoutContext: DocumentLayoutContext | undefined;

	/** Dialog hosts assign inputs directly, so a setter keeps signal state in sync without ngOnChanges. */
	@Input()
	set layoutContext(value: DocumentLayoutContext | undefined) {
		this._layoutContext = value;
		this.docState.set(value?.document ?? null);
	}
	get layoutContext(): DocumentLayoutContext | undefined {
		return this._layoutContext;
	}

	readonly host = inject(DOCUMENT_LAYOUT_HOST, { optional: true });
	readonly layout = ASSET_METADATA_CORE_LAYOUT;
	readonly docState = signal<Readonly<Record<string, unknown>> | null>(null);

	readonly props = computed(() => asRecord(this.docState()?.['properties']));
	readonly isPicture = computed(() => hasFacet(this.docState(), 'Picture'));
	readonly isVideo = computed(() => hasFacet(this.docState(), 'Video'));
	readonly isAudio = computed(() => hasFacet(this.docState(), 'Audio'));
	readonly pictureInfo = computed(() => asRecord(this.props()['picture:info']));
	readonly videoInfo = computed(() => asRecord(this.props()['vid:info']));

	documentId(): string {
		return String(this.layoutContext?.document?.['uid'] ?? '');
	}
}
