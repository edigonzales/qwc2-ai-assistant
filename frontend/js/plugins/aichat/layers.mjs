// Layer paths always refer to the current QWC flat layer tree.
function leaves(node, path = [], parentsVisible = true) {
    const visible = parentsVisible && node.visibility !== false;
    if (Array.isArray(node.sublayers)) {
        return node.sublayers.flatMap((child, index) => leaves(child, [...path, index], visible));
    }
    return node.name ? [{node, path, visible}] : [];
}

function definition(nodes, name) {
    for (const node of nodes || []) {
        if (node.name === name) return node;
        const found = definition(node.sublayers, name);
        if (found) return found;
    }
    return null;
}

function missingNodes(nodes, present) {
    return nodes.flatMap(node => {
        if (!node.sublayers?.length) {
            return present.has(node.name) || !node.name ? [] : [{...structuredClone(node), visibility: true}];
        }
        const sublayers = missingNodes(node.sublayers, present);
        return sublayers.length ? [{...node, sublayers, visibility: true}] : [];
    });
}

export function mapLayers(store, {roles, addThemeSublayer, changeLayerProperty, removeLayer, restore, baseUrl}) {
    // Snapshot the slice, since QWC replaces it even if a switch returns to the same theme.
    const themeState = store.getState().theme;
    function checkTheme(signal) {
        signal?.throwIfAborted();
        if (!themeState?.current || themeState.switching) throw new Error('mapNotReady');
        if (store.getState().theme !== themeState) throw new Error('themeChanged');
    }

    function thematicLeaves() {
        return (store.getState().layers?.flat || []).filter(layer =>
            layer.role === roles.THEME || layer.role === roles.USERLAYER
        ).flatMap(layer => leaves(layer).map(item => ({...item, layer})));
    }

    function matches(names) {
        const themeUrl = new URL(themeState.current.url, baseUrl).href;
        return thematicLeaves().filter(({layer, node}) =>
            layer.type === 'wms' && names.has(node.name) && layer.url &&
            new URL(layer.url, baseUrl).href === themeUrl
        );
    }

    async function change(entry, add, signal) {
        checkTheme(signal);
        const name = entry.qwcLayerName;
        let names = new Set([name]);
        let nodes = [];
        if (entry.type === 'layergroup' || !matches(names).length) {
            const known = definition(themeState.current.sublayers, name);
            nodes = known ? [known] : await restore(name, themeState.current, signal);
            checkTheme(signal);
            names = new Set(nodes.flatMap(node => leaves(node)).map(item => item.node.name));
            if (!names.size) throw new Error('layerUnavailable');
        }

        let current = matches(names);
        if (!add) {
            if (!current.length) throw new Error('layerNotOnMap');
            while (current.length) {
                const {layer, path} = current[0];
                store.dispatch(removeLayer(layer.id, path));
                const next = matches(names);
                if (next.length >= current.length) throw new Error('mapActionFailed');
                current = next;
            }
            return {id: entry.id, title: entry.title, removed: true};
        }

        const present = new Set(current.map(({node}) => node.name));
        const changed = present.size < names.size || current.some(item => !item.visible);
        for (const {layer, path, visible} of current) {
            if (!visible) store.dispatch(changeLayerProperty(layer.id, 'visibility', true, path, 'both'));
        }
        const missing = missingNodes(nodes, present);
        if (missing.length) store.dispatch(addThemeSublayer({sublayers: missing}));
        const enabled = new Set(matches(names).filter(item => item.visible).map(({node}) => node.name));
        if (!enabled.size) throw new Error('mapActionFailed');
        return {id: entry.id, title: entry.title, changed, enabled: enabled.size, total: names.size};
    }

    return {
        checkTheme,
        addLayer: (entry, signal) => change(entry, true, signal),
        removeLayer: (entry, signal) => change(entry, false, signal),
        getVisibleLayers() {
            const seen = new Map();
            for (const {node, visible} of thematicLeaves()) {
                if (visible && !seen.has(node.name)) {
                    seen.set(node.name, {qwcLayerName: node.name.slice(0, 200), title: (node.title || node.name).slice(0, 120)});
                }
            }
            return {layers: [...seen.values()].slice(0, 30), total: seen.size};
        }
    };
}
