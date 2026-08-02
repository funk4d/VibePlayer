(function () {
    'use strict';

    var BRIDGE_VERSION = '0.5.0';
    var LABEL_PREFIX = '@VIBEVOICE@';
    var EPISODE_PREFIX = '@VIBEEPISODE@';
    var METADATA_PREFIX = '@VIBEMETA@';

    if (window.VibePlayerBridge && window.VibePlayerBridge.version === BRIDGE_VERSION) return;
    if (!window.Lampa || !Lampa.Android || typeof Lampa.Android.openPlayer !== 'function') {
        console.warn('[VibePlayer] Lampa Android bridge is unavailable');
        return;
    }

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

    function isModssStream(value) {
        var url = streamUrl(value);
        return Boolean(url && /^https?:\/\/api\.modss\.tv(?::\d+)?(?:\/|$)/i.test(url));
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

    function sourceName(data, link) {
        return firstDisplayName([
            data && data.source_name,
            data && data.provider_name,
            data && data.balancer_name,
            data && data.source,
            data && data.provider,
            data && data.balancer,
            data && data.online
        ]) || (isModssStream(link) ? 'MODS' : null);
    }

    function metadataLabel(title, source) {
        return METADATA_PREFIX + encodeURIComponent(title || '') + '|' + encodeURIComponent(source || '');
    }

    function serializeMetadata(link, data) {
        var title = contentTitle(data);
        var source = sourceName(data, link);
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

    function containsStreamUrl(value, expectedUrl) {
        if (!value || !expectedUrl) return false;
        if (streamUrl(value) === expectedUrl) return true;

        var qualities = value.quality;
        if (qualities && typeof qualities === 'object' && !Array.isArray(qualities)) {
            if (Object.keys(qualities).some(function (label) {
                return streamUrl(qualities[label]) === expectedUrl;
            })) return true;
        }

        return Array.isArray(value.playlist) && value.playlist.some(function (item) {
            return containsStreamUrl(item, expectedUrl);
        });
    }

    function enrichFromCapturedPlayback(data, captured, link) {
        var expectedUrl = streamUrl(link) || streamUrl(data);
        if (!data || !captured || !containsStreamUrl(captured, expectedUrl)) return false;

        [
            'title', 'movie_title', 'source_name', 'provider_name', 'balancer_name',
            'source', 'provider', 'balancer', 'online', 'voiceovers', 'playlist',
            'subtitles', 'subtitle', 'tracks', 'timeline', 'poster'
        ].forEach(function (name) {
            if (data[name] == null && captured[name] != null) data[name] = captured[name];
        });

        if (captured.quality && typeof captured.quality === 'object') {
            data.quality = Object.assign({}, captured.quality, data.quality || {});
        }
        if (captured.headers && typeof captured.headers === 'object') {
            data.headers = Object.assign({}, captured.headers, data.headers || {});
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
    var originalPlayerPlay = Lampa.Player && typeof Lampa.Player.play === 'function' &&
        (Lampa.Player.play.__vibeOriginal || Lampa.Player.play);
    if (originalPlayerPlay) {
        var wrappedPlayerPlay = function (data) {
            if (data && typeof data === 'object') {
                capturedPlayback = data;
                window.VibePlayerBridge.lastCapture = captureSummary(data);
                console.info(
                    '[VibePlayer] captured fields=' + window.VibePlayerBridge.lastCapture.fields.join(',') +
                    ' headers=' + window.VibePlayerBridge.lastCapture.headerNames.join(',')
                );
            }
            return originalPlayerPlay.apply(this, arguments);
        };
        wrappedPlayerPlay.__vibeOriginal = originalPlayerPlay;
        Lampa.Player.play = wrappedPlayerPlay;
    }

    var originalOpenPlayer = Lampa.Android.openPlayer.__vibeOriginal || Lampa.Android.openPlayer;
    var wrappedOpenPlayer = function (link, payload) {
        var data = decodePayload(payload);
        var stats = {
            metadata: 0,
            captured: false,
            voiceovers: { total: 0, serialized: 0 },
            episodes: { total: 0, serialized: 0 }
        };
        try {
            if (data) {
                stats.captured = enrichFromCapturedPlayback(data, capturedPlayback, link);
                stats.metadata = serializeMetadata(link, data);
                stats.voiceovers = serializeVoiceovers(data);
                stats.episodes = serializeEpisodes(data);
            }
        } catch (error) {
            console.warn('[VibePlayer] serialization failed: ' + (error && error.name || 'Error'));
        }
        window.VibePlayerBridge.lastStats = stats;
        return originalOpenPlayer.call(this, link, data ? encodePayload(payload, data) : payload);
    };
    wrappedOpenPlayer.__vibeOriginal = originalOpenPlayer;
    Lampa.Android.openPlayer = wrappedOpenPlayer;

    window.VibePlayerBridge = {
        version: BRIDGE_VERSION,
        labelPrefix: LABEL_PREFIX,
        episodePrefix: EPISODE_PREFIX,
        metadataPrefix: METADATA_PREFIX,
        lastStats: {
            metadata: 0,
            captured: false,
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

    console.info('[VibePlayer] bridge ' + BRIDGE_VERSION + ' loaded');
})();
