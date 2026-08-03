(function () {
    'use strict';

    var BRIDGE_VERSION = '0.22.0';
    var LABEL_PREFIX = '@VIBEVOICE@';
    var EPISODE_PREFIX = '@VIBEEPISODE@';
    var METADATA_PREFIX = '@VIBEMETA@';
    var RESERVE_PREFIX = '@VIBERESERVE@';

    var INSTALL_ATTEMPTS = 60;
    var INSTALL_INTERVAL_MS = 500;
    // The online component is rebuilt per card, so its methods are re-wrapped as it appears.
    var COMPONENT_WATCH_MS = 1000;
    // A whole series across every voice can be hundreds of entries; the Intent is not a
    // place to discover a size limit the hard way.
    var MAX_SOURCE_ITEMS = 400;
    // The player's own loopback endpoint. Nothing here reaches a source or the network at
    // large - it exists so that no extra request has to be made to learn what was watched.
    var PROGRESS_ENDPOINT = 'http://127.0.0.1:47615/progress';
    var PROGRESS_POLL_MS = 4000;

    if (window.VibePlayerBridge && window.VibePlayerBridge.version === BRIDGE_VERSION) return;

    function nonEmptyString(value) {
        return typeof value === 'string' && value.trim() ? value.trim() : null;
    }

    /**
     * Source and voice names are built for a web page, so they arrive carrying markup and
     * entities - "Alloha [+UA] <span style=...>" and the like. An external player renders
     * plain text, so strip the markup rather than showing it.
     */
    function plainText(value) {
        var text = nonEmptyString(value);
        if (!text) return null;
        return nonEmptyString(
            text.replace(/<[^>]*>/g, ' ')
                .replace(/&nbsp;/gi, ' ')
                .replace(/&amp;/gi, '&')
                .replace(/&lt;/gi, '<')
                .replace(/&gt;/gi, '>')
                .replace(/&quot;/gi, '"')
                .replace(/&#(\d{1,6});/g, function (whole, code) { return String.fromCharCode(parseInt(code, 10)); })
                .replace(/\s+/g, ' ')
        );
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
        var direct = plainText(value);
        if (direct) return direct;
        if (!value || typeof value !== 'object') return null;
        return plainText(value.label) ||
            plainText(value.title) ||
            plainText(value.name);
    }

    function firstDisplayName(values) {
        for (var index = 0; index < values.length; index += 1) {
            var found = displayName(values[index]);
            if (found) return found;
        }
        return null;
    }

    function activeMovie() {
        var Lampa = window.Lampa;
        var activity = Lampa && Lampa.Activity && typeof Lampa.Activity.active === 'function'
            ? Lampa.Activity.active()
            : null;
        return activity && activity.movie && typeof activity.movie === 'object' ? activity.movie : null;
    }

    function contentTitle(data) {
        var movie = activeMovie();
        return firstDisplayName([
            movie && movie.name,
            movie && movie.title,
            data && data.card,
            data && data.movie,
            data && data.movie_title,
            data && data.title
        ]);
    }

    /**
     * Balancer names come decorated for the card: "Alloha [4K, +UA] VIP 5|5", "Eneida VIP 1|5".
     * The badges describe the listing, not the source, and only crowd the player's overlay.
     */
    function trimSourceName(name) {
        var text = plainText(name);
        if (!text) return null;
        return nonEmptyString(
            text.replace(/[\[(].*?[\])]/g, ' ')
                .replace(/\bVIP\b/gi, ' ')
                .replace(/\b\d+\s*[|/]\s*\d+\b/g, ' ')
                .replace(/\s+/g, ' ')
        ) || text;
    }

    function sourceName(data) {
        return trimSourceName(firstDisplayName([
            data && data.source_name,
            data && data.provider_name,
            data && data.balancer_name,
            // Lampa and its online plugins transliterate this one, so both spellings exist
            // in the wild and the English-only list quietly matched neither.
            data && data.balanser_name,
            data && data.source,
            data && data.provider,
            data && data.balancer,
            data && data.balanser,
            data && data.online
        ]));
    }

    function metadataLabel(title, source, probe, data) {
        return METADATA_PREFIX + encodeURIComponent(title || '') + '|' +
            encodeURIComponent(source || '') + '|' + probe + '|' +
            integer(data && data.season, 0) + '|' +
            integer(data && data.episode, 0) + '|' +
            encodeURIComponent(plainText(data && data.voice_name) || '');
    }

    // A compact, URL-free description of what the capture actually held: matched, playlist
    // entries, voiceovers, top-level fields. The WebView console is not visible over ADB, so
    // this is how the bridge's own view of the payload reaches a device log at all.
    function captureProbe(matched) {
        var summary = window.VibePlayerBridge.lastCapture;
        var source = sourceSummary();
        return 'c' + (matched ? 1 : 0) +
            'p' + summary.playlistCount +
            'v' + summary.voiceoverCount +
            'f' + summary.fields.length +
            // What the source itself yielded: entries seen, and how many had a usable
            // address. A balancer that serialises nothing is one of these two being zero.
            's' + source.items +
            'w' + source.withStream;
    }

    function serializeMetadata(link, data, matched) {
        var title = contentTitle(data);
        var source = sourceName(data);
        var url = streamUrl(link) || streamUrl(data);
        if (!url) return 0;

        var qualities = Object.assign({}, data.quality || {});
        Object.keys(qualities).forEach(function (label) {
            if (label.indexOf(METADATA_PREFIX) === 0) delete qualities[label];
        });
        qualities[metadataLabel(title, source, captureProbe(matched), data)] = url;
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

    /**
     * Mirror our labels onto the playlist entry being launched.
     *
     * Lampa builds the Intent's quality arrays from the *current playlist item's* quality
     * map, not from the payload's top-level one. With a playlist present the top level is
     * never read, so labels written only there reach the player as nothing at all.
     */
    function mirrorLabelsOntoCurrentItem(data, link) {
        var playlist = Array.isArray(data.playlist) ? data.playlist : null;
        var qualities = data.quality;
        if (!playlist || !qualities || typeof qualities !== 'object') return 0;

        var expectedUrl = streamUrl(link) || streamUrl(data);
        var current = null;
        for (var index = 0; index < playlist.length && !current; index += 1) {
            if (ownsStreamUrl(playlist[index], expectedUrl)) current = playlist[index];
        }
        if (!current) {
            var season = integer(data.season, -1);
            var episode = integer(data.episode, -1);
            for (var i = 0; i < playlist.length && !current; i += 1) {
                var entry = playlist[i];
                if (entry && integer(entry.season, -2) === season && integer(entry.episode, -2) === episode) {
                    current = entry;
                }
            }
        }
        if (!current) return 0;

        var merged = Object.assign({}, current.quality || {});
        if (!Object.keys(merged).length) {
            var own = itemStream(current) || expectedUrl;
            if (own) merged[plainText(current.quality_label) || 'Auto'] = own;
        }
        var mirrored = 0;
        Object.keys(qualities).forEach(function (label) {
            if (!isBridgeLabel(label)) return;
            merged[label] = qualities[label];
            mirrored += 1;
        });
        if (mirrored) current.quality = merged;
        return mirrored;
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
        var language = window.navigator && nonEmptyString(window.navigator.language);
        if (language) headers['Accept-Language'] = language;
        // Headers reach the player; labels added to data.quality may not. This is the one
        // channel that reliably answers "which bridge is actually loaded on the device".
        headers['X-Vibe-Bridge'] = BRIDGE_VERSION;
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
            encodeURIComponent(plainText(item && item.title) || ''),
            encodeURIComponent(quality || 'Auto'),
            // The voice belongs to the entry: without it the player cannot answer "which
            // voices exist for the episode I just switched to".
            encodeURIComponent(plainText(item && item.voice_name) || ''),
            // Lampa identifies an episode in its timeline by this hash. The player reports
            // progress against it for episodes chosen after launch.
            encodeURIComponent(nonEmptyString(timeline.hash) || '')
        ];
        return EPISODE_PREFIX + fields.join('|');
    }

    function episodeNumber(item) {
        var value = parseInt(item && item.episode, 10);
        return isFinite(value) && value > 0 ? value : null;
    }

    /**
     * Episodes for the voice currently being watched, across every season the source
     * supplied. A different voice is a different stream of the same episode and belongs in
     * the voiceover list, not here, or the episode list would show each episode many times.
     */
    function serializeEpisodes(data) {
        var items = allSourceItems();
        var playlist = Array.isArray(data.playlist) ? data.playlist : [];
        var qualities = Object.assign({}, data.quality || {});
        var seen = {};
        var serialized = 0;

        items.forEach(function (item) {
            var number = episodeNumber(item);
            var url = itemStream(item);
            if (!number || !url) return;
            var voice = plainText(item.voice_name) || '';
            var key = voice + '|' + integer(item.season, 0) + 'x' + number;
            if (seen[key]) return;
            seen[key] = true;
            qualities[episodeLabel(item, item.quality_label || 'Auto')] = url;
            serialized += 1;
        });

        // Whatever the payload already carried stays authoritative for entries we missed.
        playlist.forEach(function (item) {
            if (!item || typeof item !== 'object') return;
            var number = episodeNumber(item);
            if (!number || seen[integer(item.season, 0) + 'x' + number]) return;
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
        console.info('[VibePlayer] episodes=' + items.length + ' serialized=' + serialized);
        return { total: items.length || playlist.length, serialized: serialized };
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

    // ---------------------------------------------------------------------------------
    // Source items.
    //
    // Lampa serialises the playback payload with JSON.stringify before handing it to the
    // Android app, and an online source that resolves episode addresses on demand stores
    // those as functions - which JSON.stringify silently drops. The external player then
    // receives a season of episodes with no addresses at all.
    //
    // The addresses do exist: every playlist cell is built from a source item that already
    // carries a direct `stream`. Watch the component build them and keep the pairing. This
    // reads what the plugin has already loaded and asks the network for nothing.
    // ---------------------------------------------------------------------------------

    var sourceItems = [];
    var sourceFolder = null;
    var folderOwner = null;

    var hookHits = {};

    /**
     * Every live activity's component, not just the topmost one: opening a season or voice
     * selector pushes a modal on top, so the list that owns the data is no longer "active".
     */
    function onlineComponents() {
        var Lampa = window.Lampa;
        if (!Lampa || !Lampa.Activity) return [];
        var activities = [];
        if (typeof Lampa.Activity.all === 'function') activities = Lampa.Activity.all() || [];
        if (typeof Lampa.Activity.active === 'function') {
            var active = Lampa.Activity.active();
            if (active && activities.indexOf(active) === -1) activities.push(active);
        }
        return activities
            .map(function (activity) { return activity && activity.activity && activity.activity.component; })
            .filter(function (component) { return component && typeof component === 'object'; });
    }

    function itemStream(item) {
        if (!item || typeof item !== 'object') return null;
        // A resolved address wins; `url` may be an API endpoint rather than media, and the
        // component's own external-player helper prefers `stream` for exactly that reason.
        return nonEmptyString(item.stream) || nonEmptyString(item.url);
    }

    function rememberItem(item) {
        if (!item || typeof item !== 'object' || !itemStream(item)) return;
        if (sourceItems.length >= MAX_SOURCE_ITEMS) return;
        if (sourceItems.indexOf(item) === -1) sourceItems.push(item);
    }

    function wrapComponentMethod(component, name, observer) {
        var current = component[name];
        if (typeof current !== 'function') return false;
        // A wrapper left by an earlier load of this bridge still feeds that load's closure,
        // which no longer receives anything. Replace it rather than stacking on top of it.
        if (current.__vibeWrapped === BRIDGE_VERSION) return false;
        var original = current.__vibeOriginal || current;
        var wrapped = function () {
            hookHits[name] = (hookHits[name] || 0) + 1;
            try { observer.apply(null, arguments); } catch (error) { /* diagnostics only */ }
            return original.apply(this, arguments);
        };
        wrapped.__vibeWrapped = BRIDGE_VERSION;
        wrapped.__vibeOriginal = original;
        component[name] = wrapped;
        return true;
    }

    function rememberFolder(value) {
        // parse() is where the component turns a balancer answer into its own structure:
        // { voice: [...], season: [...], folder: { voice: { season: [ episodes ] } } }.
        // Every episode in there already carries a direct stream, for every voice and every
        // season, which is the whole catalogue the source has to offer at zero further cost.
        var folder = value && typeof value === 'object' ? value.folder : null;
        if (!folder || typeof folder !== 'object') return;

        // A component is rebuilt per balancer, so its identity says whether this answer
        // continues the current source or replaces it. Answers can arrive in parts - one
        // season, one voice - and replacing on every one of them loses the rest.
        var owner = this;
        if (owner !== folderOwner) {
            folderOwner = owner;
            sourceFolder = folder;
            sourceItems = [];
            return;
        }
        if (folder === sourceFolder) return;
        var merged = {};
        Object.keys(sourceFolder || {}).forEach(function (voice) { merged[voice] = sourceFolder[voice]; });
        Object.keys(folder).forEach(function (voice) { merged[voice] = folder[voice]; });
        sourceFolder = merged;
    }

    function hookSourceComponent() {
        onlineComponents().forEach(function (component) {
            wrapComponentMethod(component, 'parse', rememberFolder);
            wrapComponentMethod(component, 'toPlayElement', rememberItem);
        });
    }

    /** Every item the balancer supplied, flattened out of folder[voice][season]. */
    function folderItems() {
        var folder = sourceFolder;
        if (!folder || typeof folder !== 'object') return [];
        var items = [];

        function collect(value, depth, voice) {
            if (items.length >= MAX_SOURCE_ITEMS || !value || typeof value !== 'object') return;
            if (Array.isArray(value)) {
                value.forEach(function (entry) {
                    if (!entry || typeof entry !== 'object' || !itemStream(entry)) return;
                    if (!nonEmptyString(entry.voice_name) && voice) entry.voice_name = voice;
                    if (items.length < MAX_SOURCE_ITEMS) items.push(entry);
                });
                return;
            }
            if (depth > 2) return;
            Object.keys(value).forEach(function (key) {
                // The first level of the folder is the voice name itself.
                collect(value[key], depth + 1, depth === 0 ? key : voice);
            });
        }

        collect(folder, 0, null);
        return items;
    }

    function allSourceItems() {
        var items = folderItems();
        sourceItems.forEach(function (item) {
            if (items.indexOf(item) === -1) items.push(item);
        });
        return items;
    }

    /** Structural view of what the source handed us, for diagnosis. Counts and names only. */
    function sourceSummary() {
        var items = allSourceItems();
        var voices = [];
        var seasons = [];
        items.forEach(function (item) {
            var voice = nonEmptyString(item.voice_name);
            if (voice && voices.indexOf(voice) === -1) voices.push(voice);
            var season = integer(item.season, -1);
            if (season >= 0 && seasons.indexOf(season) === -1) seasons.push(season);
        });
        return {
            items: items.length,
            withStream: items.filter(itemStream).length,
            voices: voices,
            seasons: seasons.sort(function (a, b) { return a - b; }),
            folderKeys: sourceFolder && typeof sourceFolder === 'object'
                ? Object.keys(sourceFolder).slice(0, 30)
                : null,
            hits: hookHits
        };
    }

    /**
     * Collects what the player watched after it was launched.
     *
     * Lampa credits a playback result to the entry it started, so episodes chosen inside the
     * player leave no trace. The player cannot call into this page - it is a separate process
     * and a page has no address - so it offers its progress on a loopback endpoint instead,
     * and this reads it whenever Lampa is back in front of the viewer.
     */
    var appliedSession = null;

    function applyWatchProgress() {
        if (typeof fetch !== 'function' || !window.Lampa || !Lampa.Timeline) return;

        fetch(PROGRESS_ENDPOINT, { cache: 'no-store' })
            .then(function (response) { return response.ok ? response.json() : null; })
            .then(function (payload) {
                if (!payload || !Array.isArray(payload.items) || !payload.items.length) return;
                // A run's progress is applied once. Re-applying it would overwrite whatever
                // the viewer has watched in Lampa since.
                var stamp = payload.session + ':' + payload.items.length;
                if (stamp === appliedSession) return;
                appliedSession = stamp;

                var applied = 0;
                payload.items.forEach(function (item) {
                    if (!item || !nonEmptyString(item.hash)) return;
                    Lampa.Timeline.update({
                        hash: item.hash,
                        time: item.time,
                        duration: item.duration,
                        percent: item.percent
                    });
                    applied += 1;
                });
                if (applied) console.info('[VibePlayer] progress applied for ' + applied + ' episodes');
            })
            .catch(function () { /* the player is simply not running */ });
    }

    function watchForPlayerProgress() {
        if (typeof setInterval !== 'function') return;
        setInterval(function () {
            if (!document.hidden) applyWatchProgress();
        }, PROGRESS_POLL_MS);
        if (typeof document.addEventListener === 'function') {
            document.addEventListener('visibilitychange', function () {
                if (!document.hidden) applyWatchProgress();
            });
        }
    }

    var capturedPlayback = null;

    window.VibePlayerBridge = {
        version: BRIDGE_VERSION,
        labelPrefix: LABEL_PREFIX,
        episodePrefix: EPISODE_PREFIX,
        metadataPrefix: METADATA_PREFIX,
        reservePrefix: RESERVE_PREFIX,
        installed: false,
        sourceSummary: function () { return sourceSummary(); },
        lastSource: { items: 0, withStream: 0, voices: [], seasons: [] },
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
                    stats.metadata = serializeMetadata(link, data, stats.captured);
                    stats.voiceovers = serializeVoiceovers(data);
                    stats.episodes = serializeEpisodes(data);
                    stats.mirrored = mirrorLabelsOntoCurrentItem(data, link);
                }
            } catch (error) {
                console.warn('[VibePlayer] serialization failed: ' + (error && error.name || 'Error'));
            }
            window.VibePlayerBridge.lastStats = stats;
            window.VibePlayerBridge.lastSource = sourceSummary();
            if (data && data.headers && typeof data.headers === 'object') {
                data.headers['X-Vibe-Stats'] = 'e' + stats.episodes.serialized +
                    'v' + stats.voiceovers.serialized +
                    'm' + stats.mirrored +
                    's' + window.VibePlayerBridge.lastSource.items +
                    'w' + window.VibePlayerBridge.lastSource.withStream +
                    'q' + (data.quality ? Object.keys(data.quality).length : 0) +
                    'l' + (Array.isArray(data.playlist) ? data.playlist.length : 0);
            }
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
        hookSourceComponent();
        if (typeof setInterval === 'function') setInterval(hookSourceComponent, COMPONENT_WATCH_MS);
        watchForPlayerProgress();
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
