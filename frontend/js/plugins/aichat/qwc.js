import {addThemeSublayer, changeLayerProperty, LayerRole, removeLayer} from 'qwc2/actions/layers';
import {zoomToExtent, zoomToPoint} from 'qwc2/actions/map';
import CoordinatesUtils from 'qwc2/utils/CoordinatesUtils';
import MapUtils from 'qwc2/utils/MapUtils';

import {themeLayerRestorer} from '../../themeLayerRestorer';
import {mapLayers} from './layers.mjs';

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

function zoomView(store, {coordinates, target_scale: targetScale, bbox, crs, label}) {
    const {map} = store.getState();
    if (!map.size?.width || !map.size?.height || map.scales.length < 2) throw new Error('mapNotReady');

    let target;
    let requestedZoom;
    if (coordinates) {
        const {scales} = map;
        requestedZoom = targetScale === undefined ? map.zoom :
            MapUtils.computeZoom(scales, Math.max(scales.at(-1), Math.min(scales[0], targetScale)));
        store.dispatch(zoomToPoint(coordinates, requestedZoom, crs));
        target = CoordinatesUtils.reproject(coordinates, crs, map.projection);
    } else {
        const extent = CoordinatesUtils.reprojectBbox(bbox, crs, map.projection);
        target = [(extent[0] + extent[2]) / 2, (extent[1] + extent[3]) / 2];
        store.dispatch(zoomToExtent(bbox, crs));
        // QWC can select a tighter discrete zoom. Use one wider step when needed.
        const next = store.getState().map;
        const resolution = MapUtils.computeForZoom(next.resolutions, next.zoom);
        if (next.zoom >= 1 && (extent[2] - extent[0] > next.size.width * resolution ||
            extent[3] - extent[1] > next.size.height * resolution)) {
            store.dispatch(zoomToExtent(bbox, crs, -1));
        }
    }

    const next = store.getState().map;
    const scale = MapUtils.computeForZoom(next.scales, next.zoom);
    if (!Number.isFinite(scale) || (requestedZoom !== undefined && next.zoom !== requestedZoom) ||
        !next.center?.every((value, index) => Math.abs(value - target[index]) < 0.01)) {
        throw new Error('mapActionFailed');
    }
    return {label, center: CoordinatesUtils.reproject(next.center, next.projection, crs), scale: Math.round(scale)};
}

export function zoomToObject(store, {bbox, label}) {
    const [west, south, east, north] = bbox;
    const {theme} = store.getState();
    if (!theme?.current || theme.switching) throw new Error('mapNotReady');
    const location = west === east && south === north ? {coordinates: [west, south], target_scale: 1000} : {bbox};
    return zoomView(store, {...location, crs: 'EPSG:2056', label});
}

export function createQwcCommandExecutor(store, signal) {
    const layers = mapLayers(store, {
        roles: LayerRole, addThemeSublayer, changeLayerProperty, removeLayer, restore, baseUrl: window.location.href
    });
    let boundsBeforeZoom;

    return function executeCommand(command) {
        layers.checkTheme(signal);
        switch (command.type) {
        case 'add_layer': return layers.addLayer(command.layer, signal);
        case 'remove_layer': return layers.removeLayer(command.layer, signal);
        case 'get_visible_layers': return layers.getVisibleLayers();
        case 'get_map_state': {
            const {theme, map} = store.getState();
            if (!map.size?.width || !map.size?.height) throw new Error('mapNotReady');
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
        case 'zoom_to_point':
        case 'zoom_to_extent': {
            const bounds = store.getState().map.bbox?.bounds;
            const outcome = zoomView(store, command);
            boundsBeforeZoom = bounds;
            return outcome;
        }
        default: throw new Error('invalidTool');
        }
    };
}
