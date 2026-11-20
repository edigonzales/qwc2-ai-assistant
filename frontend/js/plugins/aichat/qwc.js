import {addThemeSublayer, changeLayerProperty, LayerRole, removeLayer} from 'qwc2/actions/layers';
import {zoomToPoint} from 'qwc2/actions/map';
import MapUtils from 'qwc2/utils/MapUtils';

import {themeLayerRestorer} from '../../themeLayerRestorer';
import {mapLayers} from './layers.mjs';
import {createTurnTools} from './tools.mjs';

function restore(name, theme, signal) {
    return new Promise((resolve, reject) => {
        const timeout = AbortSignal.timeout(15000);
        const requestSignal = signal ? AbortSignal.any([signal, timeout]) : timeout;
        const aborted = () => reject(signal?.aborted ? signal.reason : new Error('definitionsUnavailable'));
        requestSignal.addEventListener('abort', aborted, {once: true});
        themeLayerRestorer([name], theme, (nodes, mapping) => {
            requestSignal.removeEventListener('abort', aborted);
            if (mapping === null) {
                reject(new Error('definitionsUnavailable'));
            } else {
                resolve(mapping && Object.hasOwn(mapping, name) ? nodes : []);
            }
        });
    });
}

export function createQwcTurnTools(store, options = {}) {
    const layers = mapLayers(store, {
        roles: LayerRole, addThemeSublayer, changeLayerProperty, removeLayer, restore, baseUrl: window.location.href
    });

    let boundsBeforeZoom;

    function getState() {
        const {theme, map} = store.getState();
        if (!theme.current || theme.switching || !map.size) throw new Error('mapNotReady');
        const scale = MapUtils.computeForZoom(map.scales, map.zoom);
        const extent = map.bbox?.bounds;
        return {
            theme: {id: theme.current.id, title: theme.current.title},
            crs: map.projection,
            center: map.center,
            scale: Number.isFinite(scale) ? Math.round(scale) : null,
            extent: extent === boundsBeforeZoom ? null : extent ?? null
        };
    }

    function zoom({easting, northing, scale}) {
        layers.checkTheme(options.signal);
        const {map} = store.getState();
        if (!map.size || map.scales.length < 2) throw new Error('mapNotReady');
        const boundedScale = Math.max(map.scales.at(-1), Math.min(map.scales[0], scale));
        const zoomLevel = MapUtils.computeZoom(map.scales, boundedScale);
        // The view reports the new extent asynchronously after the reducer centers the map.
        boundsBeforeZoom = map.bbox?.bounds;
        // QWC's reducer transforms the coordinate from the input CRS to the map CRS.
        store.dispatch(zoomToPoint([easting, northing], zoomLevel, 'EPSG:2056'));
        const state = getState();
        return {crs: state.crs, center: state.center, scale: state.scale};
    }

    return createTurnTools({...options, map: {...layers, getState, zoom}});
}
