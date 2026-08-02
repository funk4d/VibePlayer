(function () {
    'use strict';

    var BRIDGE_VERSION = '0.9.0';
    var LABEL_PREFIX = '@VIBEVOICE@';
    var EPISODE_PREFIX = '@VIBEEPISODE@';
    var METADATA_PREFIX = '@VIBEMETA@';
    var RESERVE_PREFIX = '@VIBERESERVE@';

    var INSTALL_ATTEMPTS = 60;
    var INSTALL_INTERVAL_MS = 500;

    if (window.VibePlayerBridge && window.VibePlayerBridge.version === BRIDGE_VERSION) return;

    function nonEmptyString(value) {
        return typeof value === 'string' && value.trim() ? value.trim() : null;
    }

    function streamUrl(value) {
        var direct = nonEmptyString(value);
        if (direct) return direct;
        if (!value || typeof value !== 'object') return null;

        return nonEmptyString(value.url) ||
            nonEmptyString(value.src) ||
            nonEmptyString(value.file) ||
            nonEmptyString(value.link) ||
            nonEmptyString(value.stream) ||
            nonEmptyString(value.path);
    }

    function displayName(value) {
        var direct = nonEmptyString(value);
        if (direct) return direct;
        if (!value || typeof value !== 'object') return null;
        return nonEmptyString(value.label) ||
            nonEmptyString(value.title) ||
            nonEmptyString(value.name);
    }

    function firstDisplayName(values) {
        for (var index = 0; index < values.length; index += 1) {
            var found = displayName(values[index]);
            if (found) return found;
        }
        return null;
    }

    function contentTitle(data) {
        return firstDisplayName([
            data && data.title,
            data && data.movie_title,
            data && data.card,
            data && data.movie
        ]);
    }

    function sourceName(data) {
        return firstDisplayName([
            data && data.source_name,
            data && data.provider_name,
            data && data.balancer_name,
            data && data.source,
            data && data.provider,
            data && data.balancer,
            data && data.online
        ]);
    }

    function metadataLabel(title, source) {
        return METADATA_PREFIX + encodeURIComponent(title || '') + '|' + encodeURIComponent(source || '');
    }

    function serializeMetadata(link, data) {
        var title = contentTitle(data);
        var source = sourceName(data);
        var url = streamUrl(link) || streamUrl(data);
        if ((!title && !source) || !url) return 0;

        var qualities = Object.assign({}, data.quality || {});
        Object.keys(qualities).forEach(function (label) {
            if (label.indexOf(METADATA_PREFIX) === 0) delete qualities[label];
        });
        qualities[metadataLabel(title, source)] = url;
        data.quality = qualities;
        return 1;
    }

    function decodePayload(payload) {
        if (payload && typeof payload === 'object') return payload;
        if (typeof payload !== 'string' || !payload.trim()) return null;
        try {
            var parsed = JSON.parse(payload);
            return parsed && typeof parsed === 'object' ? parsed : null;
        } catch (error) {
            return null;
        }
    }

    function encodePayload(originalPayload, data) {
        return typeof originalPayload === 'string' ? JSON.stringify(data) : data;
    }

    function browserContextHeaders() {
        var origin = window.location && nonEmptyString(window.location.origin);
        var validOrigin = origin && /^https?:\/\/[^/]+$/i.test(origin) ? origin : null;
        var userAgent = window.navigator && nonEmptyString(window.navigator.userAgent);
        var headers = { Accept: '*/*' };

        // These are the request-context headers supplied automatically by the WebView
        // when Lampa's built-in hls.js player fetches a cross-origin stream. An external
        // player is a separate process, so Lampa must carry the same context in its Intent.
        if (validOrigin) {
            headers.Origin = validOrigin;
            headers.Referer = validOrigin + '/';
        }
        if (userAgent) headers['User-Agent'] = userAgent;
        return headers;
    }

    function addPlaybackHeaders(data) {
        var sourceHeaders = data.headers && typeof data.headers === 'object' && !Array.isArray(data.headers)
            ? data.headers
            : {};
        data.headers = Object.assign({}, browserContextHeaders(), sourceHeaders);
        return Object.keys(data.headers).length;
    }

    function captureSummary(value) {
        var headers = value && value.headers;
        return {
            fields: value && typeof value === 'object' ? Object.keys(value).sort() : [],
            headerNames: headers && typeof headers === 'object' ? Object.keys(headers).sort() : [],
            qualityCount: value && value.quality && typeof value.quality === 'object' ? Object.keys(value.quality).length : 0,
            playlistCount: value && Array.isArray(value.playlist) ? value.playlist.length : 0,
            voiceoverCount: value && Array.isArray(value.voiceovers) ? value.voiceovers.length : 0
        };
    }

    // Describes the whole card and stays true for every entry inside it.
    var SESSION_FIELDS = [
        'title', 'movie_title', 'source_name', 'provider_name', 'balancer_name',
        'source', 'provider', 'balancer', 'online', 'voiceovers', 'playlist',
        'subtitles', 'subtitle', 'tracks', 'poster'
    ];

    // Describes one specific stream. Belongs to whichever entry owns the launched URL,
    // and to no other entry — a sibling episode's copy of these is simply wrong data.
    var ITEM_FIELDS = ['timeline', 'url_reserve', 'quality_reserve'];

    // True when this exact entry is the one being launched, rather than merely the
    // container of the entry being launched.
    function ownsStreamUrl(value, expectedUrl) {
        if (!value || !expectedUrl) return false;
        if (streamUrl(value) === expectedUrl) return true;

        var qualities = value.quality;
        return Boolean(qualities && typeof qualities === 'object' && !Array.isArray(qualities) &&
            Object.keys(qualities).some(function (label) {
                return streamUrl(qualities[label]) === expectedUrl;
            }));
    }

    function matchingCapture(captured, expectedUrl) {
        if (!captured || !expectedUrl) return null;
        if (ownsStreamUrl(captured, expectedUrl)) return captured;
        if (!Array.isArray(captured.playlist)) return null;

        for (var index = 0; index < captured.playlist.length; index += 1) {
            if (ownsStreamUrl(captured.playlist[index], expectedUrl)) return captured.playlist[index];
        }
        return null;
    }

    function copyMissing(target, donor, names) {
        if (!donor || typeof donor !== 'object') return;
        names.forEach(function (name) {
            if (target[name] == null && donor[name] != null) target[name] = donor[name];
        });
    }

    function enrichFromCapturedPlayback(data, captured, link) {
        var expectedUrl = streamUrl(link) || streamUrl(data);
        if (!data || !captured) return false;

        // A hit anywhere in the capture used to be enough, so launching episode 2 pulled in
        // episode 1's quality map and its backup addresses. Locate the entry that actually
        // owns the launched URL, and take stream-specific data only from that entry.
        var item = matchingCapture(captured, expectedUrl);
        if (!item) return false;

        copyMissing(data, captured, SESSION_FIELDS);
        copyMissing(data, item, ITEM_FIELDS);

        if (item.quality && typeof item.quality === 'object' && !Array.isArray(item.quality)) {
            data.quality = Object.assign({}, item.quality, data.quality || {});
        }
        var headers = (item !== captured && item.headers) || captured.headers;
        if (headers && typeof headers === 'object') {
            data.headers = Object.assign({}, headers, data.headers || {});
        }
        return true;
    }

    function voiceoverName(item, index) {
        if (!item || typeof item !== 'object') return 'Voiceover ' + (index + 1);
        return nonEmptyString(item.label) ||
            nonEmptyString(item.title) ||
            nonEmptyString(item.name) ||
            nonEmptyString(item.language) ||
            ('Voiceover ' + (index + 1));
    }

    function encodedLabel(name, quality) {
        return LABEL_PREFIX + encodeURIComponent(name) + '|' + encodeURIComponent(quality || 'Auto');
    }

    function addVariant(target, name, quality, value) {
        var url = streamUrl(value);
        if (!url) return 0;
        target[encodedLabel(name, quality)] = url;
        return 1;
    }

    function integer(value, fallback) {
        var parsed = parseInt(value, 10);
        return isFinite(parsed) ? parsed : fallback;
    }

    function episodeLabel(item, quality) {
        var timeline = item && item.timeline || {};
        var fields = [
            integer(item && item.season, 0),
            integer(item && item.episode, 0),
            Math.max(0, Math.min(100, integer(timeline.percent, 0))),
            Math.max(0, integer(timeline.time, 0)),
            encodeURIComponent(nonEmptyString(item && item.title) || ''),
            encodeURIComponent(quality || 'Auto')
        ];
        return EPISODE_PREFIX + fields.join('|');
    }

    function serializeEpisodes(data) {
        var playlist = Array.isArray(data.playlist) ? data.playlist : [];
        var qualities = Object.assign({}, data.quality || {});
        var serialized = 0;

        playlist.forEach(function (item) {
            if (!item || typeof item !== 'object') return;
            var variants = item.quality;
            if (variants && typeof variants === 'object' && !Array.isArray(variants)) {
                Object.keys(variants).forEach(function (quality) {
                    var url = streamUrl(variants[quality]);
                    if (!url) return;
                    qualities[episodeLabel(item, quality)] = url;
                    serialized += 1;
                });
            } else {
                var direct = streamUrl(item);
                if (!direct) return;
                qualities[episodeLabel(item, 'Auto')] = direct;
                serialized += 1;
            }
        });

        if (serialized) data.quality = qualities;
        console.info('[VibePlayer] episodes=' + playlist.length + ' serialized=' + serialized);
        return { total: playlist.length, serialized: serialized };
    }

    // Lampa sources routinely ship a backup address next to the chosen one, in url_reserve
    // and quality_reserve. The built-in player falls back to them; an external player never
    // saw them at all, because nothing carried them across the Intent.
    function reserveCandidates(data, primaryUrl) {
        var selected = selectedQualityLabel(data, primaryUrl);
        var reserves = data.quality_reserve;
        var ordered = [];

        if (reserves && typeof reserves === 'object' && !Array.isArray(reserves)) {
            // The reserve for the quality the user is actually watching goes first.
            Object.keys(reserves).sort(function (left, right) {
                return (left === selected ? -1 : 0) - (right === selected ? -1 : 0);
            }).forEach(function (label) {
                ordered.push({ label: label, url: streamUrl(reserves[label]) });
            });
        }
        ordered.unshift({ label: 'reserve', url: streamUrl(data.url_reserve) });

        var seen = {};
        return ordered.filter(function (item) {
            if (!item.url || item.url === primaryUrl || seen[item.url]) return false;
            seen[item.url] = true;
            return true;
        });
    }

    // Labels this bridge itself wrote into data.quality. They are transport, not qualities,
    // and must never be mistaken for one — including when openPlayer runs twice on one object.
    function isBridgeLabel(label) {
        return label.indexOf(LABEL_PREFIX) === 0 ||
            label.indexOf(EPISODE_PREFIX) === 0 ||
            label.indexOf(METADATA_PREFIX) === 0 ||
            label.indexOf(RESERVE_PREFIX) === 0;
    }

    function selectedQualityLabel(data, primaryUrl) {
        var qualities = data.quality;
        if (!qualities || typeof qualities !== 'object' || Array.isArray(qualities)) return null;
        var match = Object.keys(qualities).filter(function (label) {
            return !isBridgeLabel(label) && streamUrl(qualities[label]) === primaryUrl;
        });
        return match.length ? match[0] : null;
    }

    function serializeReserves(link, data) {
        var primaryUrl = streamUrl(link) || streamUrl(data);
        var candidates = reserveCandidates(data, primaryUrl);
        var qualities = Object.assign({}, data.quality || {});
        Object.keys(qualities).forEach(function (label) {
            if (label.indexOf(RESERVE_PREFIX) === 0) delete qualities[label];
        });
        if (!candidates.length) {
            // Never invent a quality map for a payload that had none.
            if (data.quality) data.quality = qualities;
            return 0;
        }

        candidates.forEach(function (item, index) {
            qualities[RESERVE_PREFIX + index + '|' + encodeURIComponent(item.label)] = item.url;
        });
        data.quality = qualities;
        console.info('[VibePlayer] reserves=' + candidates.length);
        return candidates.length;
    }

    function serializeVoiceovers(data) {
        var voiceovers = Array.isArray(data.voiceovers) ? data.voiceovers : [];
        var qualities = Object.assign({}, data.quality || {});
        var serialized = 0;

        voiceovers.forEach(function (item, index) {
            if (!item || typeof item !== 'object') return;
            var name = voiceoverName(item, index);
            var variants = item.quality || item.qualities || item.files || item.streams;

            if (variants && typeof variants === 'object' && !Array.isArray(variants)) {
                Object.keys(variants).forEach(function (quality) {
                    serialized += addVariant(qualities, name, quality, variants[quality]);
                });
            } else {
                serialized += addVariant(qualities, name, item.quality_label || item.resolution || 'Auto', item);
            }
        });

        if (serialized) data.quality = qualities;

        // Deliberately log structure counts only. Stream URLs and authorization data must never
        // appear in WebView/ADB logs.
        console.info('[VibePlayer] voiceovers=' + voiceovers.length + ' serialized=' + serialized);
        return { total: voiceovers.length, serialized: serialized };
    }

    var capturedPlayback = null;

    window.VibePlayerBridge = {
        version: BRIDGE_VERSION,
        labelPrefix: LABEL_PREFIX,
        episodePrefix: EPISODE_PREFIX,
        metadataPrefix: METADATA_PREFIX,
        reservePrefix: RESERVE_PREFIX,
        installed: false,
        lastStats: {
            metadata: 0,
            captured: false,
            headers: 0,
            reserves: 0,
            voiceovers: { total: 0, serialized: 0 },
            episodes: { total: 0, serialized: 0 }
        },
        lastCapture: {
            fields: [],
            headerNames: [],
            qualityCount: 0,
            playlistCount: 0,
            voiceoverCount: 0
        }
    };

    function hookPlayerPlay(Lampa) {
        var original = Lampa.Player && typeof Lampa.Player.play === 'function' &&
            (Lampa.Player.play.__vibeOriginal || Lampa.Player.play);
        if (!original) return;

        var wrapped = function (data) {
            if (data && typeof data === 'object') {
                capturedPlayback = data;
                window.VibePlayerBridge.lastCapture = captureSummary(data);
                console.info(
                    '[VibePlayer] captured fields=' + window.VibePlayerBridge.lastCapture.fields.join(',') +
                    ' headers=' + window.VibePlayerBridge.lastCapture.headerNames.join(',')
                );
            }
            return original.apply(this, arguments);
        };
        wrapped.__vibeOriginal = original;
        Lampa.Player.play = wrapped;
    }

    function hookOpenPlayer(Lampa) {
        var original = Lampa.Android.openPlayer.__vibeOriginal || Lampa.Android.openPlayer;
        var wrapped = function (link, payload) {
            var data = decodePayload(payload);
            var stats = {
                metadata: 0,
                captured: false,
                headers: 0,
                reserves: 0,
                voiceovers: { total: 0, serialized: 0 },
                episodes: { total: 0, serialized: 0 }
            };
            try {
                if (data) {
                    stats.captured = enrichFromCapturedPlayback(data, capturedPlayback, link);
                    stats.headers = addPlaybackHeaders(data);
                    stats.reserves = serializeReserves(link, data);
                    stats.metadata = serializeMetadata(link, data);
                    stats.voiceovers = serializeVoiceovers(data);
                    stats.episodes = serializeEpisodes(data);
                }
            } catch (error) {
                console.warn('[VibePlayer] serialization failed: ' + (error && error.name || 'Error'));
            }
            window.VibePlayerBridge.lastStats = stats;
            return original.call(this, link, data ? encodePayload(payload, data) : payload);
        };
        wrapped.__vibeOriginal = original;
        Lampa.Android.openPlayer = wrapped;
    }

    function install() {
        var Lampa = window.Lampa;
        if (!Lampa || !Lampa.Android || typeof Lampa.Android.openPlayer !== 'function') return false;

        hookPlayerPlay(Lampa);
        hookOpenPlayer(Lampa);
        window.VibePlayerBridge.installed = true;
        console.info('[VibePlayer] bridge ' + BRIDGE_VERSION + ' installed');
        return true;
    }

    // Plugins can run before Lampa has finished building its Android interface. Giving up at
    // that moment leaves the bridge silently dead for the whole session, which looks exactly
    // like "the bridge sends nothing". Watch for the interface instead; this waits on window
    // state only and makes no requests.
    if (!install() && typeof setInterval === 'function') {
        console.info('[VibePlayer] waiting for the Lampa Android interface');
        var attempts = 0;
        var timer = setInterval(function () {
            attempts += 1;
            if (install() || attempts >= INSTALL_ATTEMPTS) clearInterval(timer);
        }, INSTALL_INTERVAL_MS);
    }
})();
