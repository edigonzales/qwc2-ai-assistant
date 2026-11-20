import React, {useEffect, useRef, useState} from 'react';
import {useSelector, useStore} from 'react-redux';

import SideBar from 'qwc2/components/SideBar';
import InputContainer from 'qwc2/components/widgets/InputContainer';
import Spinner from 'qwc2/components/widgets/Spinner';
import LocaleUtils from 'qwc2/utils/LocaleUtils';

import {errorCode, runConversation} from './aichat/conversation.mjs';
import {createQwcTurnTools} from './aichat/qwc';
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
    case 'layerNotOnMap': return LocaleUtils.tr('aichat.error.layerNotOnMap');
    case 'catalogUnavailable': return LocaleUtils.tr('aichat.error.catalog');
    case 'invalidCatalog': return LocaleUtils.tr('aichat.error.catalogResponse');
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
    default: return '';
    }
}

export default function AIChatPlugin() {
    const store = useStore();
    const locale = useSelector(state => state.locale.current);
    const active = useRef(null);
    const scroll = useRef(null);
    const latest = useRef(null);
    const nextMessage = useRef(0);
    const [input, setInput] = useState('');
    const [turns, setTurns] = useState([]);
    const [results, setResults] = useState([]);
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
    }, [pending, turns, results]);

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
        active.current = controller;
        setInput('');
        setError(null);
        setNotice(null);
        setPending(message);
        setBusy(true);
        const confirmations = [];
        try {
            let presented;
            const runTool = createQwcTurnTools(store, {
                signal: controller.signal, onResults: rows => { presented = rows; }
            });
            const history = turns.map(({user, assistant}) => ({user, assistant}));
            const result = await runConversation({
                message, history, lastResults: results, runTool, signal: controller.signal,
                onAction: (name, data) => confirmations.push(actionLabel(name, data))
            });
            controller.signal.throwIfAborted();
            const issue = result.errors[0];
            let answer = result.assistant;
            if (issue) {
                answer = confirmations.join('\n').slice(0, limits.replyLength) || errorLabel(issue);
            } else if (presented) {
                answer = presented.length ? LocaleUtils.tr('aichat.found', presented.length) : LocaleUtils.tr('aichat.noResults');
            }
            const turn = {user: message, assistant: answer, failed: Boolean(issue && !confirmations.length), key: ++nextMessage.current};
            setTurns(previous => [...previous, turn].slice(-limits.historyTurns));
            if (presented) {
                setResults(presented);
                setDetails(null);
            }
            setError(issue ?? null);
        } catch (err) {
            if (!controller.signal.aborted) {
                setError(errorCode(err));
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

    async function resultAction(name, number) {
        const layer = numberedLayer(results, number);
        if (!layer || active.current) return;
        const controller = new AbortController();
        active.current = controller;
        setError(null);
        setNotice(null);
        setBusy(true);
        try {
            const runTool = createQwcTurnTools(store, {signal: controller.signal});
            const data = await runTool(name, {id: layer.id});
            controller.signal.throwIfAborted();
            if (name === 'get_layer_details') setDetails(data);
            else setNotice(data);
        } catch (err) {
            if (!controller.signal.aborted) setError(errorCode(err, 'toolFailed'));
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
                                            {details.organization ? <div>{details.organization}</div> : null}
                                            <p>{details.description}</p>
                                            <small>{details.id}</small>
                                        </div>
                                    ) : null}
                                    {notice?.id === layer.id ? <p role="status">{LocaleUtils.tr('aichat.shown', notice.enabled, notice.total)}</p> : null}
                                </li>
                            ))}
                        </ol>
                    ) : null}
                    {pending ? <p aria-label={LocaleUtils.tr('aichat.you')} className="aichat-user" ref={latest}>{pending}</p> : null}
                </div>
                {error ? <p className="aichat-error" role="alert">{errorLabel(error)}</p> : null}
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
