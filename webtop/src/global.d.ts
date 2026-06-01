// Minimal ambient declarations for the standalone Commerce build.
//
// The Commerce app is intentionally self-contained and does not import any
// webtop-internal types (it uses `type AnyInstance = any`). The only global it
// touches is `window.appLaunch`, the launch hook the webtop shell calls from
// the host window. Declared with `any` here to stay decoupled from the cms0
// ApplicationInstance type while still type-checking this build.

export {};

declare global {
	interface Window {
		appLaunch?: (instance: any, options?: { path?: string; mimeType?: string }) => void;
	}
}
