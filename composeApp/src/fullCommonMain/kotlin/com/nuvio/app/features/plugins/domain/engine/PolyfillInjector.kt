package com.nuvio.app.features.plugins.domain.engine

object PolyfillInjector {

    fun buildPolyfillCode(scraperId: String, settingsJson: String): String {
        return """
            // Global constants (using globalThis to avoid redeclaration errors)
            globalThis.SCRAPER_ID = "$scraperId";
            globalThis.SCRAPER_SETTINGS = $settingsJson;
            if (typeof TMDB_API_KEY === 'undefined') {
                globalThis.TMDB_API_KEY = "";
            }
            if (typeof globalThis.global === 'undefined') {
                globalThis.global = globalThis;
            }
            if (typeof globalThis.window === 'undefined') {
                globalThis.window = globalThis;
            }
            if (typeof globalThis.self === 'undefined') {
                globalThis.self = globalThis;
            }

            // Fetch implementation (async)
            var fetch = async function(url, options) {
                options = options || {};
                var method = (options.method || 'GET').toUpperCase();
                var headers = options.headers || {};
                var body = options.body || '';
                var signal = options.signal || null;

                if (signal && signal.aborted) {
                    var preErr = new Error('The operation was aborted.');
                    preErr.name = 'AbortError';
                    throw preErr;
                }

                if (!headers['User-Agent']) {
                    headers['User-Agent'] = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36';
                }

                var result = __native_fetch(url, method, JSON.stringify(headers), body);
                var parsed = JSON.parse(result);

                if (signal && signal.aborted) {
                    var postErr = new Error('The operation was aborted.');
                    postErr.name = 'AbortError';
                    throw postErr;
                }

                return {
                    ok: parsed.ok,
                    status: parsed.status,
                    statusText: parsed.statusText,
                    url: parsed.url,
                    headers: {
                        get: function(name) {
                            return parsed.headers[name.toLowerCase()] || null;
                        }
                    },
                    text: function() {
                        return Promise.resolve(parsed.body);
                    },
                    json: function() {
                        try {
                            if (parsed.body === null || parsed.body === undefined || parsed.body === '') {
                                return Promise.resolve(null);
                            }
                            return Promise.resolve(JSON.parse(parsed.body));
                        } catch (e) {
                            console.error('fetch.json parse error:', e && e.message ? e.message : e);
                            return Promise.resolve(null);
                        }
                    }
                };
            };

            // AbortController/AbortSignal minimal polyfill
            if (typeof AbortSignal === 'undefined') {
                var AbortSignal = function() {
                    this.aborted = false;
                    this.reason = undefined;
                    this._listeners = [];
                };
                AbortSignal.prototype.addEventListener = function(type, listener) {
                    if (type !== 'abort' || typeof listener !== 'function') return;
                    this._listeners.push(listener);
                };
                AbortSignal.prototype.removeEventListener = function(type, listener) {
                    if (type !== 'abort') return;
                    this._listeners = this._listeners.filter(function(l) { return l !== listener; });
                };
                AbortSignal.prototype.dispatchEvent = function(event) {
                    if (!event || event.type !== 'abort') return true;
                    for (var i = 0; i < this._listeners.length; i++) {
                        try { this._listeners[i].call(this, event); } catch (e) {}
                    }
                    return true;
                };
                globalThis.AbortSignal = AbortSignal;
            }
            if (typeof AbortController === 'undefined') {
                var AbortController = function() {
                    this.signal = new AbortSignal();
                };
                AbortController.prototype.abort = function(reason) {
                    if (this.signal.aborted) return;
                    this.signal.aborted = true;
                    this.signal.reason = reason;
                    this.signal.dispatchEvent({ type: 'abort' });
                };
                globalThis.AbortController = AbortController;
            }

            // atob/btoa polyfills
            if (typeof atob === 'undefined') {
                globalThis.atob = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input).replace(/=+$/, '');
                    if (str.length % 4 === 1) {
                        throw new Error('InvalidCharacterError');
                    }
                    var output = '';
                    var bc = 0, bs, buffer, idx = 0;
                    while ((buffer = str.charAt(idx++))) {
                        buffer = chars.indexOf(buffer);
                        if (buffer === -1) continue;
                        bs = bc % 4 ? bs * 64 + buffer : buffer;
                        if (bc++ % 4) {
                            output += String.fromCharCode(255 & (bs >> ((-2 * bc) & 6)));
                        }
                    }
                    return output;
                };
            }
            if (typeof btoa === 'undefined') {
                globalThis.btoa = function(input) {
                    var chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
                    var str = String(input);
                    var output = '';
                    for (
                        var block, charCode, idx = 0, map = chars;
                        str.charAt(idx | 0) || (map = '=', idx % 1);
                        output += map.charAt(63 & (block >> (8 - (idx % 1) * 8)))
                    ) {
                        charCode = str.charCodeAt(idx += 3/4);
                        if (charCode > 0xFF) {
                            throw new Error('InvalidCharacterError');
                        }
                        block = (block << 8) | charCode;
                    }
                    return output;
                };
            }

            // URL class
            var URL = function(urlString, base) {
                var fullUrl = urlString;
                if (base && !/^https?:\/\//i.test(urlString)) {
                    var b = typeof base === 'string' ? base : base.href;
                    if (urlString.charAt(0) === '/') {
                        var m = b.match(/^(https?:\/\/[^\/]+)/);
                        fullUrl = m ? m[1] + urlString : urlString;
                    } else {
                        fullUrl = b.replace(/\/[^\/]*$/, '/') + urlString;
                    }
                }
                var parsed = __parse_url(fullUrl);
                var data = JSON.parse(parsed);
                this.href = fullUrl;
                this.protocol = data.protocol;
                this.host = data.host;
                this.hostname = data.hostname;
                this.port = data.port;
                this.pathname = data.pathname;
                this.search = data.search;
                this.hash = data.hash;
                this.origin = data.protocol + '//' + data.host;
                this.searchParams = new URLSearchParams(data.search || '');
            };
            URL.prototype.toString = function() { return this.href; };

            // URLSearchParams class
            var URLSearchParams = function(init) {
                this._params = {};
                var self = this;
                if (init && typeof init === 'object' && !Array.isArray(init)) {
                    Object.keys(init).forEach(function(key) {
                        self._params[key] = String(init[key]);
                    });
                } else if (typeof init === 'string') {
                    init.replace(/^\?/, '').split('&').forEach(function(pair) {
                        var parts = pair.split('=');
                        if (parts[0]) {
                            self._params[decodeURIComponent(parts[0])] = decodeURIComponent(parts[1] || '');
                        }
                    });
                }
            };
            URLSearchParams.prototype.toString = function() {
                var self = this;
                return Object.keys(this._params).map(function(key) {
                    return encodeURIComponent(key) + '=' + encodeURIComponent(self._params[key]);
                }).join('&');
            };
            URLSearchParams.prototype.get = function(key) {
                return this._params.hasOwnProperty(key) ? this._params[key] : null;
            };
            URLSearchParams.prototype.set = function(key, value) {
                this._params[key] = String(value);
            };
            URLSearchParams.prototype.append = function(key, value) {
                this._params[key] = String(value);
            };
            URLSearchParams.prototype.has = function(key) {
                return this._params.hasOwnProperty(key);
            };
            URLSearchParams.prototype.delete = function(key) {
                delete this._params[key];
            };
            URLSearchParams.prototype.keys = function() {
                return Object.keys(this._params);
            };
            URLSearchParams.prototype.values = function() {
                var self = this;
                return Object.keys(this._params).map(function(k) { return self._params[k]; });
            };
            URLSearchParams.prototype.entries = function() {
                var self = this;
                return Object.keys(this._params).map(function(k) { return [k, self._params[k]]; });
            };
            URLSearchParams.prototype.forEach = function(callback) {
                var self = this;
                Object.keys(this._params).forEach(function(key) {
                    callback(self._params[key], key, self);
                });
            };
            URLSearchParams.prototype.getAll = function(key) {
                return this._params.hasOwnProperty(key) ? [this._params[key]] : [];
            };
            URLSearchParams.prototype.sort = function() {
                var sorted = {};
                var self = this;
                Object.keys(this._params).sort().forEach(function(k) { sorted[k] = self._params[k]; });
                this._params = sorted;
            };

            // Cheerio implementation
            var cheerio = {
                load: function(html) {
                    var docId = __cheerio_load(html);
                    var $ = function(selector, context) {
                        if (selector && selector._elementIds) {
                            return selector;
                        }
                        if (context && context._elementIds && context._elementIds.length > 0) {
                            var allIds = [];
                            for (var i = 0; i < context._elementIds.length; i++) {
                                var childIdsJson = __cheerio_find(docId, context._elementIds[i], selector);
                                var childIds = JSON.parse(childIdsJson);
                                allIds = allIds.concat(childIds);
                            }
                            return createCheerioWrapperFromIds(docId, allIds);
                        }
                        return createCheerioWrapper(docId, selector);
                    };
                    $.html = function(el) {
                        if (el && el._elementIds && el._elementIds.length > 0) {
                            return __cheerio_html(docId, el._elementIds[0]);
                        }
                        return __cheerio_html(docId, '');
                    };
                    return $;
                }
            };

            function createCheerioWrapper(docId, selector) {
                var elementIds;
                if (typeof selector === 'string') {
                    var idsJson = __cheerio_select(docId, selector);
                    elementIds = JSON.parse(idsJson);
                } else {
                    elementIds = [];
                }
                var wrapper = {
                    _docId: docId,
                    _elementIds: elementIds,
                    length: elementIds.length,
                    each: function(callback) {
                        for (var i = 0; i < elementIds.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [elementIds[i]]);
                            callback.call(elWrapper, i, elWrapper);
                        }
                        return wrapper;
                    },
                    find: function(sel) {
                        var allIds = [];
                        for (var i = 0; i < elementIds.length; i++) {
                            var childIdsJson = __cheerio_find(docId, elementIds[i], sel);
                            var childIds = JSON.parse(childIdsJson);
                            allIds = allIds.concat(childIds);
                        }
                        return createCheerioWrapperFromIds(docId, allIds);
                    },
                    text: function() {
                        if (elementIds.length === 0) return '';
                        return __cheerio_text(docId, elementIds.join(','));
                    },
                    html: function() {
                        if (elementIds.length === 0) return '';
                        return __cheerio_inner_html(docId, elementIds[0]);
                    },
                    attr: function(name) {
                        if (elementIds.length === 0) return undefined;
                        var val = __cheerio_attr(docId, elementIds[0], name);
                        return val === '__UNDEFINED__' ? undefined : val;
                    },
                    first: function() {
                        return createCheerioWrapperFromIds(docId, elementIds.length > 0 ? [elementIds[0]] : []);
                    },
                    last: function() {
                        return createCheerioWrapperFromIds(docId, elementIds.length > 0 ? [elementIds[elementIds.length - 1]] : []);
                    },
                    next: function() {
                        var nextIds = [];
                        for (var i = 0; i < elementIds.length; i++) {
                            var nextId = __cheerio_next(docId, elementIds[i]);
                            if (nextId && nextId !== '__NONE__') {
                                nextIds.push(nextId);
                            }
                        }
                        return createCheerioWrapperFromIds(docId, nextIds);
                    },
                    prev: function() {
                        var prevIds = [];
                        for (var i = 0; i < elementIds.length; i++) {
                            var prevId = __cheerio_prev(docId, elementIds[i]);
                            if (prevId && prevId !== '__NONE__') {
                                prevIds.push(prevId);
                            }
                        }
                        return createCheerioWrapperFromIds(docId, prevIds);
                    },
                    eq: function(index) {
                        if (index >= 0 && index < elementIds.length) {
                            return createCheerioWrapperFromIds(docId, [elementIds[index]]);
                        }
                        return createCheerioWrapperFromIds(docId, []);
                    },
                    get: function(index) {
                        if (typeof index === 'number') {
                            if (index >= 0 && index < elementIds.length) {
                                return createCheerioWrapperFromIds(docId, [elementIds[index]]);
                            }
                            return undefined;
                        }
                        return elementIds.map(function(id) {
                            return createCheerioWrapperFromIds(docId, [id]);
                        });
                    },
                    map: function(callback) {
                        var results = [];
                        for (var i = 0; i < elementIds.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [elementIds[i]]);
                            var result = callback.call(elWrapper, i, elWrapper);
                            if (result !== undefined && result !== null) {
                                results.push(result);
                            }
                        }
                        return {
                            length: results.length,
                            get: function(index) {
                                if (typeof index === 'number') {
                                    return results[index];
                                }
                                return results;
                            },
                            toArray: function() { return results; }
                        };
                    },
                    filter: function(selectorOrCallback) {
                        if (typeof selectorOrCallback === 'function') {
                            var filteredIds = [];
                            for (var i = 0; i < elementIds.length; i++) {
                                var elWrapper = createCheerioWrapperFromIds(docId, [elementIds[i]]);
                                var result = selectorOrCallback.call(elWrapper, i, elWrapper);
                                if (result) {
                                    filteredIds.push(elementIds[i]);
                                }
                            }
                            return createCheerioWrapperFromIds(docId, filteredIds);
                        }
                        return wrapper;
                    },
                    children: function(sel) { return this.find(sel || '*'); },
                    parent: function() { return createCheerioWrapperFromIds(docId, []); },
                    toArray: function() {
                        return elementIds.map(function(id) {
                            return createCheerioWrapperFromIds(docId, [id]);
                        });
                    }
                };
                return wrapper;
            }

            function createCheerioWrapperFromIds(docId, ids) {
                var wrapper = {
                    _docId: docId,
                    _elementIds: ids,
                    length: ids.length,
                    each: function(callback) {
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            callback.call(elWrapper, i, elWrapper);
                        }
                        return wrapper;
                    },
                    find: function(sel) {
                        var allIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var childIdsJson = __cheerio_find(docId, ids[i], sel);
                            var childIds = JSON.parse(childIdsJson);
                            allIds = allIds.concat(childIds);
                        }
                        return createCheerioWrapperFromIds(docId, allIds);
                    },
                    text: function() {
                        if (ids.length === 0) return '';
                        return __cheerio_text(docId, ids.join(','));
                    },
                    html: function() {
                        if (ids.length === 0) return '';
                        return __cheerio_inner_html(docId, ids[0]);
                    },
                    attr: function(name) {
                        if (ids.length === 0) return undefined;
                        var val = __cheerio_attr(docId, ids[0], name);
                        return val === '__UNDEFINED__' ? undefined : val;
                    },
                    first: function() {
                        return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[0]] : []);
                    },
                    last: function() {
                        return createCheerioWrapperFromIds(docId, ids.length > 0 ? [ids[ids.length - 1]] : []);
                    },
                    next: function() {
                        var nextIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var nextId = __cheerio_next(docId, ids[i]);
                            if (nextId && nextId !== '__NONE__') {
                                nextIds.push(nextId);
                            }
                        }
                        return createCheerioWrapperFromIds(docId, nextIds);
                    },
                    prev: function() {
                        var prevIds = [];
                        for (var i = 0; i < ids.length; i++) {
                            var prevId = __cheerio_prev(docId, ids[i]);
                            if (prevId && prevId !== '__NONE__') {
                                prevIds.push(prevId);
                            }
                        }
                        return createCheerioWrapperFromIds(docId, prevIds);
                    },
                    eq: function(index) {
                        if (index >= 0 && index < ids.length) {
                            return createCheerioWrapperFromIds(docId, [ids[index]]);
                        }
                        return createCheerioWrapperFromIds(docId, []);
                    },
                    get: function(index) {
                        if (typeof index === 'number') {
                            if (index >= 0 && index < ids.length) {
                                return createCheerioWrapperFromIds(docId, [ids[index]]);
                            }
                            return undefined;
                        }
                        return ids.map(function(id) {
                            return createCheerioWrapperFromIds(docId, [id]);
                        });
                    },
                    map: function(callback) {
                        var results = [];
                        for (var i = 0; i < ids.length; i++) {
                            var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                            var result = callback.call(elWrapper, i, elWrapper);
                            if (result !== undefined && result !== null) {
                                results.push(result);
                            }
                        }
                        return {
                            length: results.length,
                            get: function(index) {
                                if (typeof index === 'number') {
                                    return results[index];
                                }
                                return results;
                            },
                            toArray: function() { return results; }
                        };
                    },
                    filter: function(selectorOrCallback) {
                        if (typeof selectorOrCallback === 'function') {
                            var filteredIds = [];
                            for (var i = 0; i < ids.length; i++) {
                                var elWrapper = createCheerioWrapperFromIds(docId, [ids[i]]);
                                var result = selectorOrCallback.call(elWrapper, i, elWrapper);
                                if (result) {
                                    filteredIds.push(ids[i]);
                                }
                            }
                            return createCheerioWrapperFromIds(docId, filteredIds);
                        }
                        return wrapper;
                    },
                    children: function(sel) { return this.find(sel || '*'); },
                    parent: function() { return createCheerioWrapperFromIds(docId, []); },
                    toArray: function() {
                        return ids.map(function(id) {
                            return createCheerioWrapperFromIds(docId, [id]);
                        });
                    }
                };
                return wrapper;
            }

            // Require function for CommonJS modules
            var require = function(moduleName) {
                if (moduleName === 'cheerio' || moduleName === 'cheerio-without-node-native' || moduleName === 'react-native-cheerio') {
                    return cheerio;
                }
                if (moduleName === 'crypto-js') {
                    if (globalThis.CryptoJS) return globalThis.CryptoJS;
                    throw new Error("Module 'crypto-js' is not loaded");
                }
                throw new Error("Module '" + moduleName + "' is not available");
            };

            // Array.prototype.flat polyfill
            if (!Array.prototype.flat) {
                Array.prototype.flat = function(depth) {
                    depth = depth === undefined ? 1 : Math.floor(depth);
                    if (depth < 1) return Array.prototype.slice.call(this);
                    return (function flatten(arr, d) {
                        return d > 0
                            ? arr.reduce(function(acc, val) {
                                return acc.concat(Array.isArray(val) ? flatten(val, d - 1) : val);
                            }, [])
                            : arr.slice();
                    })(this, depth);
                };
            }

            // Array.prototype.flatMap polyfill
            if (!Array.prototype.flatMap) {
                Array.prototype.flatMap = function(callback, thisArg) {
                    return this.map(callback, thisArg).flat();
                };
            }

            // Object.entries polyfill
            if (!Object.entries) {
                Object.entries = function(obj) {
                    var result = [];
                    for (var key in obj) {
                        if (obj.hasOwnProperty(key)) {
                            result.push([key, obj[key]]);
                        }
                    }
                    return result;
                };
            }

            // Object.fromEntries polyfill
            if (!Object.fromEntries) {
                Object.fromEntries = function(entries) {
                    var result = {};
                    for (var i = 0; i < entries.length; i++) {
                        result[entries[i][0]] = entries[i][1];
                    }
                    return result;
                };
            }

            // String.prototype.replaceAll polyfill
            if (!String.prototype.replaceAll) {
                String.prototype.replaceAll = function(search, replace) {
                    if (search instanceof RegExp) {
                        if (!search.global) {
                            throw new TypeError('replaceAll must be called with a global RegExp');
                        }
                        return this.replace(search, replace);
                    }
                    return this.split(search).join(replace);
                };
            }
        """.trimIndent()
    }
}