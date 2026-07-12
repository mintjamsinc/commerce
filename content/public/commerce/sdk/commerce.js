/*!
 * commerce.js — self-hosted embed toolkit (v2, data-only client)
 *
 * The commerce platform ships PARTS, not a storefront. This is a tiny, dependency-
 * free DATA client: it fetches SANITIZED product data from the public catalog read
 * endpoint and hands it to you as JSON. YOU render and style the page (build your
 * feature / promo pages as ordinary same-origin CMS pages).
 *
 * (v1 shipped declarative widgets — cards / banners / stock badges / mini-cart.
 * Those were retired: rendering is the site's job. This client only fetches data
 * and builds the Shopify hosted-checkout permalink.)
 *
 * Config on the <script> tag (same-origin endpoint; shop domain is a public value):
 *   <script src="/…/content/public/commerce/sdk/commerce.js"
 *           data-commerce-endpoint="/bin/cms.cgi/{workspace}/content/public/commerce/endpoints/catalog.groovy"
 *           data-commerce-shop-domain="your-shop.myshopify.com"
 *           data-commerce-currency="JPY"></script>
 *
 * JS API:
 *   Commerce.product(idOrHandle)                  — one sanitized product detail (Promise, live stock)
 *   Commerce.products({tag,type,vendor,q,limit})  — sanitized product cards (Promise)
 *   Commerce.checkoutUrl(variantId, qty)          — Shopify hosted-checkout cart permalink (string)
 *   Commerce.formatMoney(amount, currency?)
 *
 * Data returned is customer-safe only (no admin metadata, cost, or internal PIM).
 * Vanilla JS, no dependencies — safe to drop into any same-origin page.
 */
(function () {
	'use strict';

	if (window.Commerce) { return; }   // idempotent include

	var script = document.currentScript;
	var ENDPOINT = (script && script.getAttribute('data-commerce-endpoint'))
		|| '/content/public/commerce/endpoints/catalog.groovy';
	var SHOP_DOMAIN = (script && script.getAttribute('data-commerce-shop-domain')) || '';
	var CURRENCY = (script && script.getAttribute('data-commerce-currency')) || '';

	var detailCache = {};   // idOrHandle -> detail promise

	function getJSON(params) {
		var qs = Object.keys(params)
			.filter(function (k) { return params[k] != null && params[k] !== ''; })
			.map(function (k) { return encodeURIComponent(k) + '=' + encodeURIComponent(params[k]); })
			.join('&');
		var url = ENDPOINT + (qs ? '?' + qs : '');
		return fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
			.then(function (r) {
				return r.json().then(function (body) {
					if (!r.ok) { throw new Error((body && body.error) || ('HTTP ' + r.status)); }
					return body;
				});
			});
	}

	// One sanitized product detail. A GID (gid://shopify/Product/…, the wire id
	// form) or a numeric key → id; otherwise → handle.
	function product(idOrHandle) {
		if (idOrHandle == null) { return Promise.reject(new Error('product: id or handle required')); }
		var key = String(idOrHandle);
		if (!detailCache[key]) {
			var params = (/^\d+$/.test(key) || /^gid:\/\//.test(key)) ? { id: key } : { handle: key };
			detailCache[key] = getJSON(params);
		}
		return detailCache[key];
	}

	// Sanitized product cards (bounded; cards omit live stock — fetch a product for that).
	function products(query) {
		var q = query || {};
		return getJSON({
			view: 'list', tag: q.tag, type: q.type, vendor: q.vendor, q: q.q, limit: q.limit,
		}).then(function (body) { return (body && body.products) || []; });
	}

	// Shopify hosted-checkout cart permalink: https://{shopDomain}/cart/{variantId}:{qty}
	// (no Storefront API token). The shop domain is the public one configured on the tag.
	// Accepts the wire GID form (gid://shopify/ProductVariant/123) or a bare numeric id;
	// the cart permalink needs the numeric tail, and THIS toolkit is the one sanctioned
	// place that peels it — site code passes variant.id through opaquely.
	function checkoutUrl(variantId, qty) {
		if (variantId == null || !SHOP_DOMAIN) { return null; }
		var id = String(variantId);
		if (id.indexOf('gid://') === 0) { id = id.split('?')[0].split('/').pop(); }
		var n = Math.max(1, parseInt(qty, 10) || 1);
		return 'https://' + SHOP_DOMAIN + '/cart/' + id + ':' + n;
	}

	function formatMoney(amount, currency) {
		// Accepts a bare number or the wire money object { currency, amount }.
		if (amount != null && typeof amount === 'object') {
			currency = currency || amount.currency;
			amount = amount.amount;
		}
		if (amount == null || amount === '') { return ''; }
		var num = Number(amount);
		if (!isFinite(num)) { return String(amount); }
		var cur = currency || CURRENCY;
		try {
			return new Intl.NumberFormat(undefined, cur
				? { style: 'currency', currency: cur, maximumFractionDigits: 2 }
				: { maximumFractionDigits: 2 }).format(num);
		} catch (e) { return String(amount); }
	}

	window.Commerce = {
		version: '2',
		endpoint: ENDPOINT,
		shopDomain: SHOP_DOMAIN,
		product: product,
		products: products,
		checkoutUrl: checkoutUrl,
		formatMoney: formatMoney,
	};
})();
