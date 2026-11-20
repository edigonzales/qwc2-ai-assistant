/**
 * Copyright 2016, Sourcepole AG.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

import React from 'react';
import {createRoot} from 'react-dom/client';
import StandardApp from 'qwc2/components/StandardApp';

import appConfig from './appConfig';

import '../icons/build/qwc2-icons.css';

// Keep the normal map permalink, but only load the anonymous live configuration.
const pageUrl = new URL(window.location.href);
const configParams = [...pageUrl.searchParams.keys()].filter(key => key === 'localConfig' || key.startsWith('config:'));
if (configParams.length) {
    configParams.forEach(key => pageUrl.searchParams.delete(key));
    window.history.replaceState(window.history.state, '', pageUrl);
}

createRoot(document.getElementById('container')).render(<StandardApp appConfig={appConfig} />);
