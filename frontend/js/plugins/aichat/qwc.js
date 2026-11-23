import {addThemeSublayer, changeLayerProperty, LayerRole, removeLayer} from 'qwc2/actions/layers';
import {zoomToExtent, zoomToPoint} from 'qwc2/actions/map';
import CoordinatesUtils from 'qwc2/utils/CoordinatesUtils';
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

function dispatchPoint(store, point, scale) {
    const {scales} = store.getState().map;
    const boundedScale = Math.max(scales.at(-1), Math.min(scales[0], scale));
    store.dispatch(zoomToPoint(point, MapUtils.computeZoom(scales, boundedScale), 'EPSG:2056'));
}

export function zoomToObject(store, {bbox, label}) {
    if (!Array.isArray(bbox) || bbox.length !== 4 || !bbox.every(Number.isFinite)) throw new Error('invalidLocation');
    const [west, south, east, north] = bbox;
    if (west < 2480000 || east > 2840000 || south < 1070000 || north > 1300000 ||
        west > east || south > north) throw new Error('invalidLocation');
    const {map, theme} = store.getState();
    if (!theme?.current || theme.switching || !map.size?.width || !map.size?.height || map.scales.length < 2) {
        throw new Error('mapNotReady');
    }
    if (west === east && south === north) {
        dispatchPoint(store, [west, south], 1000);
    } else {
        store.dispatch(zoomToExtent(bbox, 'EPSG:2056'));
        // QWC can round to a tighter discrete zoom. Fit the native view with one wider step if necessary.
        const next = store.getState().map;
        const extent = CoordinatesUtils.reprojectBbox(bbox, 'EPSG:2056', next.projection);
        const resolution = MapUtils.computeForZoom(next.resolutions, next.zoom);
        if (next.zoom >= 1 && (extent[2] - extent[0] > next.size.width * resolution ||
            extent[3] - extent[1] > next.size.height * resolution)) {
            store.dispatch(zoomToExtent(bbox, 'EPSG:2056', -1));
        }
    }
    const next = store.getState().map;
    return {label, center: next.center, scale: Math.round(MapUtils.computeForZoom(next.scales, next.zoom))};
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
        // The view reports the new extent asynchronously after the reducer centers the map.
        boundsBeforeZoom = map.bbox?.bounds;
        dispatchPoint(store, [easting, northing], scale);
        const state = getState();
        return {crs: state.crs, center: state.center, scale: state.scale};
    }

    function zoomObject({bbox, label}) {
        layers.checkTheme(options.signal);
        boundsBeforeZoom = store.getState().map.bbox?.bounds;
        return zoomToObject(store, {bbox, label});
    }

    return createTurnTools({...options, themeId: store.getState().theme?.current?.id,
        map: {...layers, getState, zoom, zoomObject}});
}
