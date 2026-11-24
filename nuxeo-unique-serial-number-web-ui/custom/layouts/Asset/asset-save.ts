import { signal } from '@angular/core';
import type { DocumentLayoutHost, LayoutDocument, SchemaLayoutComponent } from '@maretha/webui-sdk';

/** The host supplies the saved document directly from the REST write response. */
export function hasRequestedUsn(saved: LayoutDocument, requestedDate: unknown): boolean {
	const props = saved.properties;
	const serial = props?.['usn:usn_string'];
	return (
		typeof requestedDate === 'string' &&
		typeof serial === 'string' &&
		/^\d{14}$/.test(serial) &&
		serial.slice(0, 8) === requestedDate.slice(0, 10).replaceAll('-', '') &&
		Number(serial) === props['usn:usn_long'] &&
		String(props['usn:usn_date']).slice(0, 10) === requestedDate.slice(0, 10)
	);
}

export class AssetSave {
	readonly pending = signal(false);
	readonly error = signal('');
	readonly reloadRequired = signal(false);
	private saved?: LayoutDocument;

	async run(
		schema: SchemaLayoutComponent,
		intent: Readonly<Record<string, unknown>>,
		write: () => Promise<LayoutDocument>,
		host: DocumentLayoutHost | null,
	): Promise<void> {
		if (this.pending() || this.reloadRequired() || schema.hasUploads()) return;
		this.pending.set(true);
		this.error.set('');
		const release = schema.protectDraft();
		try {
			const saved = await write();
			release('committed');
			if (intent['usn:generate_usn'] === true && !hasRequestedUsn(saved, intent['usn:usn_date'])) {
				this.saved = saved;
				this.reloadRequired.set(true);
				this.error.set('The Asset was saved. Reload it to verify the assigned USN.');
				return;
			}
			host?.complete([saved]);
		} catch (failure) {
			const status = (failure as { status?: number } | null)?.status;
			const rejected = typeof status === 'number' && status >= 400 && status < 500;
			release(rejected ? 'failed' : 'uncertain');
			this.reloadRequired.set(!rejected);
			this.error.set(
				rejected
					? failure instanceof Error
						? failure.message
						: 'Unable to save the Asset.'
					: 'The save result is unknown. Check the destination before trying again.',
			);
		} finally {
			this.pending.set(false);
		}
	}

	close(host: DocumentLayoutHost | null): void {
		if (this.saved) host?.complete([this.saved]);
		else host?.cancel();
	}
}
