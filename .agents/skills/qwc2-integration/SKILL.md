---
name: qwc2-integration
description: Implement or review QWC2 map/layer actions, the live Solothurn proxy, and catalog-driven AI tools using existing QWC2 APIs.
---

Before changing code, read the relevant QWC2 implementation and the equivalent
behavior in `edigonzales/qwc-kartenkatalog`. Confirm the pinned QWC2-LTS
version, exact Redux state shape, action signatures, map CRS and theme behavior;
do not infer them from names alone.

For layers, preserve the tested path: current state -> themeLayerRestorer for
missing definitions -> current state again -> visibility via
`changeLayerProperty` or missing layers via `addThemeSublayer`. Prevent
unintended duplicates and changes after theme switches. Groups, parent
visibility, external WMS and layer roles matter. Use the cantonal search for
candidate IDs and `weblayers` for the display name, abstract and QWC definition.
A facade is one map layer; the native feature-info service resolves its
children. Point queries use a concrete published Data Service ID. An empty
FeatureCollection is a successful query; a failed request cannot establish
that there are no matches.

Keep remote proxy rules explicit: public GET/HEAD for approved Solothurn routes
and fixed same-origin POST routes for chat and geo reads to the configured
backend. Never forward cookies, credentials or arbitrary URLs. Keep tool inputs
and results bounded. Validate tool arguments against the allowlisted schemas in
the selected backend. Keep shared text, history and request limits in
`shared/limits.json`.

Review actual map-state changes in a browser, not just Redux action calls.
Use the normal LayerTree and QWC URL state as observable integration points.
Report exactly which behavior was not verified.
