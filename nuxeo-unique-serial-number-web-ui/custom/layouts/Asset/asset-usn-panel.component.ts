import {
	ChangeDetectionStrategy,
	Component,
	computed,
	effect,
	input,
	output,
	signal,
	untracked,
} from '@angular/core';
import { WebUiMessagePipe } from '@maretha/webui-sdk';

export type UsnPanelMode = 'create' | 'edit' | 'import' | 'readonly';

export interface UsnDraftState {
	readonly valid: boolean;
	readonly dirty: boolean;
	readonly properties: Readonly<Record<string, unknown>>;
}

function todayIsoDate(): string {
	const now = new Date();
	const y = now.getFullYear();
	const m = String(now.getMonth() + 1).padStart(2, '0');
	const d = String(now.getDate()).padStart(2, '0');
	return `${y}-${m}-${d}`;
}

function formatDateOnly(value: unknown): string {
	return typeof value === 'string' && value.length >= 10 ? value.slice(0, 10) : '';
}

function readProps(
	doc: Readonly<Record<string, unknown>> | null,
): Readonly<Record<string, unknown>> {
	const raw = doc?.['properties'];
	return raw && typeof raw === 'object' && !Array.isArray(raw)
		? (raw as Readonly<Record<string, unknown>>)
		: {};
}

@Component({
	selector: 'custom-asset-usn-panel',
	imports: [WebUiMessagePipe],
	template: `
		@if (mode() === 'readonly') {
			@if (persistedUsnString()) {
				<section class="usn-banner" aria-label="Unique Serial Number">
					<div class="usn-items">
						<div class="usn-item">
							<span class="usn-label">{{
								'custom.asset.usn.string' | webuiMessage: 'USN (String)'
							}}</span>
							<span class="usn-mono" data-testid="usn-string">{{ persistedUsnString() }}</span>
						</div>
						<div class="usn-item">
							<span class="usn-label">{{
								'custom.asset.usn.long' | webuiMessage: 'USN (Number)'
							}}</span>
							<span class="usn-mono" data-testid="usn-long">{{ persistedUsnLong() }}</span>
						</div>
						@if (persistedUsnDate()) {
							<div class="usn-item">
								<span class="usn-label">{{
									'custom.asset.usn.date' | webuiMessage: 'USN Date'
								}}</span>
								<span data-testid="usn-date">{{ persistedUsnDate() }}</span>
							</div>
						}
					</div>
					<button type="button" class="usn-copy-btn" (click)="copyUsn()">
						{{
							copied()
								? ('custom.asset.usn.copied' | webuiMessage: 'Copied')
								: ('custom.asset.usn.copy' | webuiMessage: 'Copy USN')
						}}
					</button>
				</section>
			}
		} @else {
			<section class="usn-panel">
				<h4>{{ 'custom.asset.usn.section' | webuiMessage: 'Unique Serial Number (USN)' }}</h4>

				@if (isLockedImmutable()) {
					<div class="usn-values">
						<div class="usn-box">
							<span class="usn-label">{{
								'custom.asset.usn.date' | webuiMessage: 'USN Date'
							}}</span>
							<div data-testid="usn-date-readonly">{{ persistedUsnDate() }}</div>
						</div>
						<div class="usn-box">
							<span class="usn-label">{{
								'custom.asset.usn.string' | webuiMessage: 'USN (String)'
							}}</span>
							<div class="usn-mono" data-testid="usn-string-readonly">
								{{ persistedUsnString() }}
							</div>
						</div>
						<div class="usn-box">
							<span class="usn-label">{{
								'custom.asset.usn.long' | webuiMessage: 'USN (Number)'
							}}</span>
							<div class="usn-mono" data-testid="usn-long-readonly">{{ persistedUsnLong() }}</div>
						</div>
					</div>
					<p class="usn-hint">
						{{
							'custom.asset.usn.immutable'
								| webuiMessage: 'Assigned USNs cannot be edited or removed.'
						}}
					</p>
				} @else {
					<div class="usn-controls">
						<label class="usn-checkbox">
							<input
								type="checkbox"
								data-testid="create-usn-checkbox"
								[checked]="createUsn()"
								[disabled]="disabled()"
								(change)="onCheckboxChange($event)"
							/>
							<span>{{ 'custom.asset.usn.create' | webuiMessage: 'Create USN' }}</span>
						</label>

						@if (createUsn()) {
							<label class="usn-date-field">
								<span>{{ 'custom.asset.usn.date' | webuiMessage: 'USN Date' }}</span>
								<input
									type="date"
									required
									min="1000-01-01"
									max="9999-12-31"
									data-testid="usn-date-input"
									[value]="usnDate()"
									[disabled]="disabled()"
									(change)="onDateChange($event)"
								/>
							</label>
						}
					</div>

					@if (mode() === 'import' && createUsn()) {
						<p class="usn-hint">
							{{
								'custom.asset.usn.importHint'
									| webuiMessage
										: 'Each imported Asset will receive its own unique USN for the selected USN Date.'
							}}
						</p>
					}
				}
			</section>
		}
	`,
	styles: `
		.usn-panel,
		.usn-banner {
			border: 1px solid var(--nuxeo-border, #d8d8dc);
			border-radius: 4px;
			padding: 12px 16px;
			background: var(--nuxeo-box, #fff);
			margin-block: 12px;
		}
		.usn-panel h4 {
			margin: 0 0 10px;
			font-size: 0.95rem;
			font-weight: 600;
		}
		.usn-banner {
			display: flex;
			flex-wrap: wrap;
			align-items: center;
			justify-content: space-between;
			gap: 12px;
		}
		.usn-items,
		.usn-controls {
			display: flex;
			flex-wrap: wrap;
			align-items: center;
			gap: 16px;
		}
		.usn-checkbox {
			display: inline-flex;
			align-items: center;
			gap: 8px;
			font-weight: 600;
			cursor: pointer;
		}
		.usn-date-field {
			display: flex;
			flex-direction: column;
			gap: 4px;
			font-size: 0.85rem;
		}
		.usn-date-field input[type='date'] {
			font: inherit;
			padding: 5px 8px;
			border: 1px solid var(--nuxeo-border, #a6a8ad);
			border-radius: 3px;
			background: transparent;
			color: inherit;
		}
		.usn-values {
			display: grid;
			grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
			gap: 10px;
			margin-top: 10px;
		}
		.usn-box {
			padding: 8px 10px;
			border-radius: 3px;
			background: var(--nuxeo-page-background, #f5f5f5);
		}
		.usn-label {
			display: block;
			font-size: 0.72rem;
			text-transform: uppercase;
			color: var(--nuxeo-text-light, #666);
		}
		.usn-mono {
			font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
			font-weight: 600;
			letter-spacing: 0.03em;
		}
		.usn-hint {
			margin: 8px 0 0;
			font-size: 0.82rem;
			color: var(--nuxeo-text-light, #666);
		}
		.usn-error {
			margin: 8px 0 0;
			font-size: 0.85rem;
			color: var(--nuxeo-warn-text, #c62828);
		}
		.usn-copy-btn {
			font: inherit;
			padding: 5px 12px;
			border: 1px solid var(--nuxeo-border, #d8d8dc);
			border-radius: 3px;
			background: transparent;
			color: inherit;
			cursor: pointer;
		}
	`,
	changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AssetUsnPanelComponent {
	readonly mode = input<UsnPanelMode>('create');
	readonly document = input<Readonly<Record<string, unknown>> | null>(null);
	readonly disabled = input(false);
	readonly initialGenerateUsn = input(false);
	readonly initialUsnDate = input<string | null>(null);
	readonly usnChange = output<UsnDraftState>();

	readonly createUsn = signal(false);
	readonly usnDate = signal(todayIsoDate());
	readonly copied = signal(false);

	readonly persistedUsnString = computed(() => {
		const raw = readProps(this.document())['usn:usn_string'];
		return raw == null ? '' : String(raw);
	});

	readonly persistedUsnLong = computed(() => {
		const raw = readProps(this.document())['usn:usn_long'];
		return raw == null ? '' : String(raw);
	});

	readonly persistedUsnDate = computed(() =>
		formatDateOnly(readProps(this.document())['usn:usn_date']),
	);

	/** In edit mode, once a USN exists on the persisted document, it is permanently immutable. */
	readonly isLockedImmutable = computed(
		() => this.mode() === 'edit' && Boolean(this.persistedUsnString() || this.persistedUsnLong()),
	);

	constructor() {
		effect(() => {
			const mode = this.mode();
			const assignedDate = this.persistedUsnDate();
			const checked = mode === 'import' && this.initialGenerateUsn();
			const date = mode === 'import' ? formatDateOnly(this.initialUsnDate()) : assignedDate;
			untracked(() => {
				this.createUsn.set(checked);
				this.usnDate.set(date || todayIsoDate());
				this.emitDraft();
			});
		});
	}

	onCheckboxChange(event: Event): void {
		if (this.isLockedImmutable() || this.disabled()) return;
		this.createUsn.set((event.target as HTMLInputElement).checked);
		this.emitDraft();
	}

	onDateChange(event: Event): void {
		if (this.isLockedImmutable() || this.disabled()) return;
		this.usnDate.set((event.target as HTMLInputElement).value);
		this.emitDraft();
	}

	copyUsn(): void {
		const value = this.persistedUsnString();
		if (value && navigator.clipboard?.writeText) {
			void navigator.clipboard.writeText(value);
			this.copied.set(true);
			setTimeout(() => this.copied.set(false), 1800);
		}
	}

	private emitDraft(): void {
		const checked = this.createUsn() && !this.isLockedImmutable() && this.mode() !== 'readonly';
		const date = this.usnDate();
		const valid = !checked || /^[1-9]\d{3}-\d{2}-\d{2}$/.test(date);
		// Import drafts merge properties; explicitly clear a previous per-file selection.
		const properties = checked
			? { 'usn:generate_usn': true, 'usn:usn_date': valid ? date + 'T12:00:00.000Z' : null }
			: this.mode() === 'import'
				? { 'usn:generate_usn': false, 'usn:usn_date': null }
				: {};
		this.usnChange.emit({ valid, dirty: checked, properties });
	}
}
