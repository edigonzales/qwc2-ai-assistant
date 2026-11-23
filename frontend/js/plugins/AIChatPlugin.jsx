import React, {useEffect, useRef, useState} from 'react';
import {useSelector, useStore} from 'react-redux';

import SideBar from 'qwc2/components/SideBar';
import InputContainer from 'qwc2/components/widgets/InputContainer';
import Spinner from 'qwc2/components/widgets/Spinner';
import LocaleUtils from 'qwc2/utils/LocaleUtils';

import {errorCode, runConversation} from './aichat/conversation.mjs';
import {createDiagnostics} from './aichat/diagnostics.mjs';
import {createQwcTurnTools, zoomToObject} from './aichat/qwc';
import {limits, numberedLayer} from './aichat/tools.mjs';

import './style/AIChat.css';

function errorLabel(code) {
    switch (code) {
    case 'modelNotConfigured': return LocaleUtils.tr('aichat.error.config');
    case 'providerAuthentication': return LocaleUtils.tr('aichat.error.authentication');
    case 'providerRateLimit': return LocaleUtils.tr('aichat.error.rate');
    case 'providerToolSupport': return LocaleUtils.tr('aichat.error.toolSupport');
    case 'providerRejected': return LocaleUtils.tr('aichat.error.providerRejected');
    case 'invalidModelResponse': return LocaleUtils.tr('aichat.error.modelResponse');
    case 'providerTimeout': return LocaleUtils.tr('aichat.error.timeout');
    case 'providerUnavailable':
    case 'assistantUnavailable': return LocaleUtils.tr('aichat.error.unavailable');
    case 'chatLimit':
    case 'toolLimit':
    case 'searchLimit': return LocaleUtils.tr('aichat.error.limit');
    case 'requestTooLarge':
    case 'resultTooLarge': return LocaleUtils.tr('aichat.error.size');
    case 'unknownResult': return LocaleUtils.tr('aichat.error.number');
    case 'numberedSearch': return LocaleUtils.tr('aichat.error.numberedSearch');
    case 'layerUnknown': return LocaleUtils.tr('aichat.error.layerUnknown');
    case 'unknownCandidate': return LocaleUtils.tr('aichat.error.candidate');
    case 'unknownObject': return LocaleUtils.tr('aichat.error.object');
    case 'layerNotOnMap': return LocaleUtils.tr('aichat.error.layerNotOnMap');
    case 'catalogUnavailable': return LocaleUtils.tr('aichat.error.catalog');
    case 'invalidCatalog': return LocaleUtils.tr('aichat.error.catalogResponse');
    case 'geoUnavailable':
    case 'locationUnavailable':
    case 'dataUnavailable': return LocaleUtils.tr('aichat.error.geo');
    case 'invalidGeoResponse':
    case 'invalidLocation':
    case 'invalidData': return LocaleUtils.tr('aichat.error.geoResponse');
    case 'datasetUnavailable': return LocaleUtils.tr('aichat.error.dataset');
    case 'mapNotReady': return LocaleUtils.tr('aichat.error.map');
    case 'themeChanged': return LocaleUtils.tr('aichat.error.theme');
    case 'invalidTool': return LocaleUtils.tr('aichat.error.invalidTool');
    case 'layerUnavailable':
    case 'definitionsUnavailable': return LocaleUtils.tr('aichat.error.definition');
    case 'mapActionFailed':
    case 'toolFailed': return LocaleUtils.tr('aichat.error.mapAction');
    default: return LocaleUtils.tr('aichat.error.general');
    }
}

function actionLabel(name, data) {
    switch (name) {
    case 'add_layer': return LocaleUtils.tr('aichat.layerShown', data.title, data.enabled, data.total);
    case 'remove_layer': return LocaleUtils.tr('aichat.layerRemoved', data.title);
    case 'zoom_to_lv95': return LocaleUtils.tr('aichat.centered', data.center[0], data.center[1], data.scale);
    case 'zoom_to_result': return LocaleUtils.tr('aichat.objectShown', data.label, data.scale);
    default: return '';
    }
}

export default function AIChatPlugin() {
    const store = useStore();
    const locale = useSelector(state => state.locale.current);
    const theme = useSelector(state => state.theme);
    const active = useRef(null);
    const scroll = useRef(null);
    const latest = useRef(null);
    const nextMessage = useRef(0);
    const [input, setInput] = useState('');
    const [turns, setTurns] = useState([]);
    const [results, setResults] = useState([]);
    const [objectResults, setObjectResults] = useState(null);
    const [objectNotice, setObjectNotice] = useState(null);
    const [pending, setPending] = useState('');
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState(null);
    const [details, setDetails] = useState(null);
    const [notice, setNotice] = useState(null);

    useEffect(() => () => active.current?.abort(), []);
    useEffect(() => {
        if (scroll.current && latest.current) {
            scroll.current.scrollTop += latest.current.getBoundingClientRect().top - scroll.current.getBoundingClientRect().top;
        }
    }, [pending, turns, results, objectResults]);

    function cancel() {
        active.current?.abort();
        active.current = null;
        setPending('');
        setBusy(false);
    }

    function clear() {
        cancel();
        setTurns([]);
        setResults([]);
        setObjectResults(null);
        setObjectNotice(null);
        setInput('');
        setError(null);
        setDetails(null);
        setNotice(null);
    }

    async function submit(event) {
        event.preventDefault();
        const message = input.trim();
        if (!message || active.current) return;
        const controller = new AbortController();
        const diagnostics = createDiagnostics();
        active.current = controller;
        setInput('');
        setError(null);
        setNotice(null);
        setPending(message);
        setObjectNotice(null);
        setBusy(true);
        const confirmations = [];
        try {
            let presented;
            let objects;
            const runTool = createQwcTurnTools(store, {
                signal: controller.signal, diagnostics, onResults: rows => { presented = rows; },
                onObjects: rows => { objects = rows; }
            });
            const history = turns.map(({user, assistant}) => ({user, assistant}));
            const result = await runConversation({
                message, history, lastResults: results, themeId: theme.current?.id, runTool, signal: controller.signal, diagnostics,
                onAction: (name, data) => confirmations.push(actionLabel(name, data))
            });
            controller.signal.throwIfAborted();
            const issue = result.errors[0];
            let answer = confirmations.join('\n').slice(0, limits.replyLength);
            if (!answer) {
                if (issue) answer = errorLabel(issue);
                else if (presented) answer = presented.length ? LocaleUtils.tr('aichat.found', presented.length) : LocaleUtils.tr('aichat.noResults');
                else answer = result.assistant;
            }
            const turn = {user: message, assistant: answer, failed: Boolean(issue && !confirmations.length), key: ++nextMessage.current};
            setTurns(previous => [...previous, turn].slice(-limits.historyTurns));
            if (presented) {
                setResults(presented);
                setDetails(null);
            }
            if (objects) setObjectResults({theme, rows: objects});
            setError(issue ? {code: issue, partial: confirmations.length > 0} : null);
            diagnostics.record('chat.display', {display: confirmations.length ? 'map_confirmation' : issue ? 'error' : presented ? 'results' : 'model_text',
                result_count: presented?.length, confirmations: confirmations.length, error: issue});
        } catch (err) {
            diagnostics.record('chat.display', {display: controller.signal.aborted ? 'aborted' : 'error',
                error: controller.signal.aborted ? undefined : errorCode(err), confirmations: confirmations.length});
            if (!controller.signal.aborted) {
                setError({code: errorCode(err), partial: confirmations.length > 0});
                if (confirmations.length) {
                    const turn = {user: message, assistant: confirmations.join('\n').slice(0, limits.replyLength), key: ++nextMessage.current};
                    setTurns(previous => [...previous, turn].slice(-limits.historyTurns));
                } else {
                    setInput(message);
                }
            }
        } finally {
            if (active.current === controller) cancel();
        }
    }

    function showObject(object) {
        if (active.current || objectResults?.theme !== store.getState().theme) return;
        setError(null);
        setObjectNotice(null);
        try {
            const result = zoomToObject(store, object);
            setObjectNotice({ref: object.ref, text: actionLabel('zoom_to_result', result)});
        } catch (err) {
            setError({code: errorCode(err, 'toolFailed')});
        }
    }

    async function resultAction(name, number) {
        const layer = numberedLayer(results, number);
        if (!layer || active.current) return;
        const controller = new AbortController();
        const diagnostics = createDiagnostics();
        active.current = controller;
        setError(null);
        setNotice(null);
        setBusy(true);
        const start = performance.now();
        try {
            const runTool = createQwcTurnTools(store, {signal: controller.signal, diagnostics});
            const data = await runTool(name, {id: layer.id});
            controller.signal.throwIfAborted();
            if (name === 'get_layer_details') setDetails(data);
            else setNotice(data);
            diagnostics.record('result.action', {tool: name, duration_ms: performance.now() - start, map_confirmed: name === 'add_layer'});
        } catch (err) {
            diagnostics.record('result.action', {tool: name, duration_ms: performance.now() - start,
                error: controller.signal.aborted ? 'aborted' : errorCode(err, 'toolFailed')});
            if (!controller.signal.aborted) setError({code: errorCode(err, 'toolFailed')});
        } finally {
            if (active.current === controller) cancel();
        }
    }

    return (
        <SideBar icon="catalog" id="AIChat" minWidth="min(18em, 100vw)" onHide={cancel} side="right"
            title={LocaleUtils.tr('appmenu.items.AIChat')} width="min(26em, 100vw)">
            <div className="aichat" lang={locale} role="body">
                <div aria-live="polite" className="aichat-scroll" ref={scroll} role="log">
                    {turns.map(turn => (
                        <React.Fragment key={turn.key}>
                            <p aria-label={LocaleUtils.tr('aichat.you')} className="aichat-user"
                                ref={!pending && turn.failed && turn === turns.at(-1) ? latest : null}>{turn.user}</p>
                            {turn.failed ? null : <p aria-label={LocaleUtils.tr('appmenu.items.AIChat')} className="aichat-answer"
                                ref={!pending && turn === turns.at(-1) ? latest : null}>{turn.assistant}</p>}
                        </React.Fragment>
                    ))}
                    {results.length > 0 ? (
                        <ol aria-label={LocaleUtils.tr('aichat.results')} className="aichat-results">
                            {results.map((layer, index) => (
                                <li key={layer.id}>
                                    <strong>{layer.title}</strong>
                                    {layer.themeTitle ? <div className="aichat-theme">{layer.themeTitle}</div> : null}
                                    {layer.description ? <p>{layer.description}</p> : null}
                                    <div className="aichat-buttons">
                                        <button className="button" disabled={busy} onClick={() => resultAction('get_layer_details', index + 1)} type="button">
                                            {LocaleUtils.tr('aichat.details')}
                                        </button>
                                        <button className="button" disabled={busy} onClick={() => resultAction('add_layer', index + 1)} type="button">
                                            {LocaleUtils.tr('aichat.show')}
                                        </button>
                                    </div>
                                    {details?.id === layer.id ? (
                                        <div className="aichat-detail">
                                            <p>{details.description}</p>
                                            <small>{details.id}</small>
                                        </div>
                                    ) : null}
                                    {notice?.id === layer.id ? <p role="status">{LocaleUtils.tr('aichat.shown', notice.enabled, notice.total)}</p> : null}
                                </li>
                            ))}
                        </ol>
                    ) : null}
                    {objectResults && objectResults.theme === theme && objectResults.rows.length > 0 ? (
                        <ul aria-label={LocaleUtils.tr('aichat.objectResults')} className="aichat-results">
                            {objectResults.rows.map(object => (
                                <li key={object.ref}>
                                    <strong>{object.label}</strong>
                                    {object.bbox ? (
                                        <div className="aichat-buttons">
                                            <button className="button" disabled={busy} onClick={() => showObject(object)} type="button">
                                                {LocaleUtils.tr('aichat.show')}
                                            </button>
                                        </div>
                                    ) : <div className="aichat-theme">{LocaleUtils.tr('aichat.error.geoResponse')}</div>}
                                    {objectNotice?.ref === object.ref ? <p role="status">{objectNotice.text}</p> : null}
                                </li>
                            ))}
                        </ul>
                    ) : null}
                    {pending ? <p aria-label={LocaleUtils.tr('aichat.you')} className="aichat-user" ref={latest}>{pending}</p> : null}
                </div>
                {error ? <p className="aichat-error" role="alert">
                    {error.partial ? LocaleUtils.tr('aichat.partial', errorLabel(error.code)) : errorLabel(error.code)}
                </p> : null}
                <form className="aichat-compose" onSubmit={submit}>
                    {busy ? <div className="aichat-busy" role="status"><Spinner /> {LocaleUtils.tr('aichat.loading')}</div> : null}
                    <InputContainer>
                        <textarea aria-label={LocaleUtils.tr('aichat.question')} maxLength={limits.messageLength}
                            onChange={event => setInput(event.target.value)} placeholder={LocaleUtils.tr('aichat.question')}
                            role="input" rows={2} value={input} />
                    </InputContainer>
                    <div className="aichat-buttons">
                        <button className="button" disabled={busy || !input.trim()} type="submit">{LocaleUtils.tr('aichat.send')}</button>
                        {busy ? <button className="button" onClick={cancel} type="button">{LocaleUtils.tr('aichat.cancel')}</button> : null}
                        <button className="button" onClick={clear} type="button">{LocaleUtils.tr('aichat.clear')}</button>
                    </div>
                </form>
            </div>
        </SideBar>
    );
}
