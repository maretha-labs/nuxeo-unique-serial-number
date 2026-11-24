import { TestBed } from '@angular/core/testing';
import {
	DOCUMENT_IMPORT_DRAFT,
	DOCUMENT_LAYOUT_GATEWAY,
	DOCUMENT_LAYOUT_HOST,
	DocumentLayoutRegistry,
	type LayoutDocument,
	type SchemaLayoutComponent,
	documentLayoutContext,
} from '@maretha/webui-sdk';
import { AssetSave, hasRequestedUsn } from './asset-save';
import { AssetEditLayoutComponent } from './asset-edit.layout';
import { AssetImportLayoutComponent } from './asset-import.layout';
import { provideAssetUsnLayouts } from './index';
import { AssetUsnPanelComponent } from './asset-usn-panel.component';

describe('Asset USN Angular WebUI layouts', () => {
	it('resolves view layouts dynamically based on Picture, Video, Audio, or default facet', () => {
		TestBed.configureTestingModule({ providers: [provideAssetUsnLayouts()] });
		const registry = TestBed.inject(DocumentLayoutRegistry);

		const pic = registry.resolve(
			documentLayoutContext({
				documentType: 'Asset',
				mode: 'view',
				document: { uid: '1', type: 'Asset', facets: ['Picture'] },
			}),
		);
		expect(pic?.id).toBe('custom.asset.view.picture');
		expect(pic?.component).toBeUndefined();

		const vid = registry.resolve(
			documentLayoutContext({
				documentType: 'Asset',
				mode: 'view',
				document: { uid: '1', type: 'Asset', facets: ['Video', 'HasStoryboard'] },
			}),
		);
		expect(vid?.id).toBe('custom.asset.view.video');
		expect(vid?.component).toBeUndefined();

		const aud = registry.resolve(
			documentLayoutContext({
				documentType: 'Asset',
				mode: 'view',
				document: { uid: '1', type: 'Asset', facets: ['Audio'] },
			}),
		);
		expect(aud?.id).toBe('custom.asset.view.audio');
		expect(aud?.component).toBeUndefined();

		const generic = registry.resolve(
			documentLayoutContext({
				documentType: 'Asset',
				mode: 'view',
				document: { uid: '1', type: 'Asset', facets: ['Downloadable'] },
			}),
		);
		expect(generic?.id).toBe('custom.asset.view.default');
		expect(generic?.component).toBeUndefined();
	});

	it('requests generation only on save and never sends an assigned number', () => {
		TestBed.configureTestingModule({});
		const fixture = TestBed.createComponent(AssetUsnPanelComponent);
		fixture.componentRef.setInput('mode', 'create');
		fixture.detectChanges();

		const emitted: unknown[] = [];
		fixture.componentInstance.usnChange.subscribe((state) => emitted.push(state));

		const checkbox: HTMLInputElement = fixture.nativeElement.querySelector(
			'[data-testid="create-usn-checkbox"]',
		);
		checkbox.checked = true;
		checkbox.dispatchEvent(new Event('change'));
		fixture.detectChanges();
		const dateInput: HTMLInputElement = fixture.nativeElement.querySelector(
			'[data-testid="usn-date-input"]',
		);
		dateInput.value = '2030-05-12';
		dateInput.dispatchEvent(new Event('change'));

		fixture.detectChanges();

		const last = emitted[emitted.length - 1] as {
			valid: boolean;
			properties: Record<string, unknown>;
		};
		expect(last.valid).toBeTrue();
		expect(last.properties).toEqual({
			'usn:generate_usn': true,
			'usn:usn_date': '2030-05-12T12:00:00.000Z',
		});
		checkbox.checked = false;
		checkbox.dispatchEvent(new Event('change'));
		expect(emitted.at(-1)).toEqual({ valid: true, dirty: false, properties: {} });
	});

	it('preserves an unnumbered historical date until generation is explicitly requested', () => {
		const fixture = TestBed.createComponent(AssetUsnPanelComponent);
		fixture.componentRef.setInput('mode', 'edit');
		fixture.componentRef.setInput('document', {
			properties: { 'usn:usn_date': '2001-02-03T12:00:00.000Z' },
		});
		const emit = jasmine.createSpy('emit');
		fixture.componentInstance.usnChange.subscribe(emit);
		fixture.detectChanges();
		expect(emit.calls.mostRecent().args[0].properties).toEqual({});
		const checkbox = fixture.nativeElement.querySelector(
			'input[type=checkbox]',
		) as HTMLInputElement;
		checkbox.click();
		expect(emit.calls.mostRecent().args[0].properties).toEqual({
			'usn:generate_usn': true,
			'usn:usn_date': '2001-02-03T12:00:00.000Z',
		});
		checkbox.click();
		expect(emit.calls.mostRecent().args[0].properties).toEqual({});
	});

	it('protects uploads and never replays a committed write whose returned serial is missing', async () => {
		const task = new AssetSave();
		const release = jasmine.createSpy('release');
		const uploads = jasmine.createSpy('hasUploads').and.returnValue(true);
		const schema = {
			hasUploads: uploads,
			protectDraft: () => release,
		} as unknown as SchemaLayoutComponent;
		const saved: LayoutDocument = { uid: 'saved-id', type: 'Asset', properties: {} };
		const write = jasmine.createSpy('write').and.resolveTo(saved);
		const host = jasmine.createSpyObj('host', ['cancel', 'complete']);
		const intent = { 'usn:generate_usn': true, 'usn:usn_date': '2026-01-01T12:00:00.000Z' };
		await task.run(schema, intent, write, host);
		expect(write).not.toHaveBeenCalled();
		uploads.and.returnValue(false);
		await task.run(schema, intent, write, host);
		expect(release).toHaveBeenCalledOnceWith('committed');
		expect(task.reloadRequired()).toBeTrue();
		await task.run(schema, intent, write, host);
		expect(write).toHaveBeenCalledTimes(1);
		task.close(host);
		expect(host.complete).toHaveBeenCalledOnceWith([saved]);
		const numbered = {
			...saved,
			properties: { ...intent, 'usn:usn_string': '20260101100001', 'usn:usn_long': 20260101100001 },
		};
		expect(hasRequestedUsn(numbered, intent['usn:usn_date'])).toBeTrue();
		expect(hasRequestedUsn(numbered, '2026-01-02')).toBeFalse();
	});

	it('hides the Create USN checkbox on edit when a USN is already assigned', () => {
		const host = jasmine.createSpyObj('DocumentLayoutHost', ['complete', 'cancel']);
		const pending = () => new Promise<never>(() => undefined);

		TestBed.configureTestingModule({
			providers: [
				{ provide: DOCUMENT_LAYOUT_GATEWAY, useValue: { readType: pending, read: pending } },
				{ provide: DOCUMENT_LAYOUT_HOST, useValue: host },
			],
		});

		const fixture = TestBed.createComponent(AssetEditLayoutComponent);
		fixture.componentInstance.layoutContext = documentLayoutContext({
			documentType: 'Asset',
			mode: 'edit',
			document: {
				uid: 'asset-uuid-1',
				type: 'Asset',
				properties: {
					'usn:usn_string': '20261124100000',
					'usn:usn_long': 20261124100000,
					'usn:usn_date': '2026-11-24T12:00:00.000Z',
				},
			},
		});
		fixture.detectChanges();

		expect(fixture.nativeElement.querySelector('[data-testid="create-usn-checkbox"]')).toBeNull();
		expect(
			fixture.nativeElement.querySelector('[data-testid="usn-string-readonly"]')?.textContent,
		).toContain('20261124100000');
	});

	it('sets usn:generate_usn in import layout without sharing a single literal USN', () => {
		const pending = () => new Promise<never>(() => undefined);
		const draft = {
			file: new File(['x'], 'photo.png', { type: 'image/png' }),
			properties: { 'dc:title': 'photo.png' },
			update: jasmine.createSpy('update'),
			setValid: jasmine.createSpy('setValid'),
		};

		TestBed.configureTestingModule({
			providers: [
				{ provide: DOCUMENT_LAYOUT_GATEWAY, useValue: { readType: pending, read: pending } },
				{ provide: DOCUMENT_IMPORT_DRAFT, useValue: draft },
			],
		});

		const fixture = TestBed.createComponent(AssetImportLayoutComponent);
		fixture.componentInstance.layoutContext = documentLayoutContext({
			documentType: 'Asset',
			mode: 'import',
			parentDocument: { uid: 'folder-1' },
		});
		fixture.detectChanges();

		const checkbox: HTMLInputElement = fixture.nativeElement.querySelector(
			'[data-testid="create-usn-checkbox"]',
		);
		checkbox.checked = true;
		checkbox.dispatchEvent(new Event('change'));
		fixture.detectChanges();

		const lastArg = draft.update.calls.mostRecent().args[0] as Record<string, unknown>;
		expect(lastArg['usn:generate_usn']).toBeTrue();
		expect(lastArg['usn:usn_string']).toBeUndefined();
		expect(lastArg['usn:usn_long']).toBeUndefined();
	});
});
