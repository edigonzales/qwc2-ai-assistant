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
visibility, external WMS and layer roles matter. Read metadata only from the
existing `edigonzales/kartenkatalog` API, using stable `id` / `qwcLayerName`.
Do not invent service URLs or implement another layer registry.

Keep remote proxy rules explicit: public GET/HEAD for approved Solothurn and
catalog routes; one fixed same-origin POST chat route to the local Python
backend; never forward cookies, credentials or arbitrary URLs. Query inputs
are escaped and bounded. Test negative paths as well as the successful case.

Review actual map-state changes in a browser, not just Redux action calls.
Use the normal LayerTree and QWC URL state as observable integration points.
Report exactly which behavior was not verified.
