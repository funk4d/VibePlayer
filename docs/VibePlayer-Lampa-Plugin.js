(function () {
    'use strict';

    var BRIDGE_VERSION = '0.44.0';
    var LABEL_PREFIX = '@VIBEVOICE@';
    var EPISODE_PREFIX = '@VIBEEPISODE@';
    var METADATA_PREFIX = '@VIBEMETA@';
    var RESERVE_PREFIX = '@VIBERESERVE@';
    var BUNDLE_PREFIX = '@VIBEBUNDLE@';

    var INSTALL_ATTEMPTS = 60;
    var INSTALL_INTERVAL_MS = 500;
    // The online component is rebuilt per card, so its methods are re-wrapped as it appears.
    var COMPONENT_WATCH_MS = 1000;
    // Lampa's native AndroidJS interface is the last hop before Binder.  Some source
    // plugins call it directly, bypassing Lampa.Android.openPlayer, so both surfaces are
    // watched and re-wrapped when Lampa rebuilds them.
    var OPEN_PLAYER_WATCH_MS = 500;
    // A whole series across every voice can be hundreds of entries; the Intent is not a
    // place to discover a size limit the hard way.
    var MAX_SOURCE_ITEMS = 400;
    // The player's own loopback endpoint. Nothing here reaches a source or the network at
    // large - it exists so that no extra request has to be made to learn what was watched.
    var PROGRESS_ENDPOINT = 'http://127.0.0.1:47615/progress';
    var PROGRESS_POLL_MS = 4000;
    var MAX_FORWARD_QUALITY_ENTRIES = 96;

    // A series can contain hundreds of signed addresses. Sending each address as an
    // individual Uri makes Android's Binder reject the launch even after the playlist has
    // been reduced to one current item. Keep the labels (they are the player UI), but put
    // the addresses into one LZ-string bundle and pass short vibe://ref/N values beside them.
    // This is synchronous and self-contained: it never contacts a source or a proxy.
    var TRANSPORT_REF_PREFIX = 'vibe://ref/';
    var TRANSPORT_BUNDLE_PREFIX = 'vibe://bundle/';

    if (window.VibePlayerBridge && window.VibePlayerBridge.version === BRIDGE_VERSION) return;

    function nonEmptyString(value) {
        return typeof value === 'string' && value.trim() ? value.trim() : null;
    }

    // Small synchronous subset of lz-string 1.4.x. The URI-safe alphabet keeps the result
    // valid inside an Android Uri extra without another escaping layer. The matching
    // decompressor lives in the APK (QualityVariantParser), so this code is deliberately
    // kept local instead of adding a runtime dependency to the Lampa page.
    var LZ_URI_ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+-$';

    function lzCompressUri(input) {
        if (input == null) return '';
        var dictionary = {};
        var toCreate = {};
        var w = '';
        var enlargeIn = 2;
        var dictSize = 3;
        var numBits = 2;
        var data = [];
        var dataVal = 0;
        var dataPosition = 0;
        var i;
        var value;

        function writeBit(bit) {
            dataVal = (dataVal << 1) | bit;
            if (dataPosition === 5) {
                dataPosition = 0;
                data.push(LZ_URI_ALPHABET.charAt(dataVal));
                dataVal = 0;
            } else {
                dataPosition += 1;
            }
        }

        function writeBits(number, count) {
            var current = number;
            for (var bit = 0; bit < count; bit += 1) {
                writeBit(current & 1);
                current >>= 1;
            }
        }

        function growDictionary() {
            enlargeIn -= 1;
            if (enlargeIn === 0) {
                enlargeIn = Math.pow(2, numBits);
                numBits += 1;
            }
        }

        for (var index = 0; index < input.length; index += 1) {
            var c = input.charAt(index);
            if (!Object.prototype.hasOwnProperty.call(dictionary, c)) {
                dictionary[c] = dictSize;
                dictSize += 1;
                toCreate[c] = true;
            }

            var wc = w + c;
            if (Object.prototype.hasOwnProperty.call(dictionary, wc)) {
                w = wc;
                continue;
            }

            if (Object.prototype.hasOwnProperty.call(toCreate, w)) {
                if (w.charCodeAt(0) < 256) {
                    writeBits(0, numBits);
                    writeBits(w.charCodeAt(0), 8);
                } else {
                    writeBits(1, numBits);
                    writeBits(w.charCodeAt(0), 16);
                }
                growDictionary();
                delete toCreate[w];
            } else {
                writeBits(dictionary[w], numBits);
            }
            growDictionary();
            dictionary[wc] = dictSize;
            dictSize += 1;
            w = c;
        }

        if (w !== '') {
            if (Object.prototype.hasOwnProperty.call(toCreate, w)) {
                if (w.charCodeAt(0) < 256) {
                    writeBits(0, numBits);
                    writeBits(w.charCodeAt(0), 8);
                } else {
                    writeBits(1, numBits);
                    writeBits(w.charCodeAt(0), 16);
                }
                growDictionary();
                delete toCreate[w];
            } else {
                writeBits(dictionary[w], numBits);
            }
            growDictionary();
        }

        // End-of-stream marker and the zero padding required by lz-string.
        writeBits(2, numBits);
        while (true) {
            dataVal <<= 1;
            if (dataPosition === 5) {
                data.push(LZ_URI_ALPHABET.charAt(dataVal));
                break;
            }
            dataPosition += 1;
        }
        return data.join('');
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

    // MODS has used both `voice_name` and `voice` over its lifetime.  Other online
    // components call the same thing a translation, dub or language.  Keep this
    // normalisation in the bridge so the Android side never has to know which source
    // happened to produce the object.
    function explicitVoiceName(item) {
        if (!item || typeof item !== 'object') return null;
        return firstDisplayName([
            item.voice_name,
            item.voice,
            item.voiceover,
            item.translation,
            item.dubbing,
            item.dub,
            item.language
        ]);
    }

    function itemVoiceName(item, fallback) {
        return explicitVoiceName(item) ||
            (sourceItemVoices && item && typeof item === 'object' && sourceItemVoices.get(item)) ||
            plainText(fallback);
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
            encodeURIComponent(plainText(data && data.voice_name) || '') + '|' +
            BRIDGE_VERSION;
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
            // How many voices the source's own structure held, which is what tells a source
            // with one voice apart from a structure we failed to capture.
            'n' + source.voices.length +
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

    // Android's Binder rejects an Activity launch when the marshalled Intent is close to
    // 1 MB. A MODS/Alloha series can contain hundreds of full playlist objects, and passing
    // those objects through openPlayer makes the launch fail before VibePlayer receives it.
    // The player needs the current item, its compact quality map, and the transport labels;
    // all other episode data has already been encoded into @VIBEEPISODE@ labels below.
    function addQualityUrls(target, qualities) {
        if (!qualities || typeof qualities !== 'object' || Array.isArray(qualities)) return;
        if (Array.isArray(qualities)) {
            qualities.forEach(function (entry) {
                var url = streamUrl(entry);
                if (url) target[url] = true;
            });
            return;
        }
        Object.keys(qualities).forEach(function (label) {
            var url = streamUrl(qualities[label]);
            if (url) target[url] = true;
        });
    }

    /**
     * Lampa's Android bridge only needs strings in the quality map.  Keeping the source's
     * object values here is both unnecessary and dangerous: a single source can put every
     * episode's signed URL graph in data.quality and push the Binder transaction back over
     * its limit.  Retain all bridge transport labels, but retain ordinary qualities only
     * when they belong to the episode currently being launched.
     */
    function compactQualityMap(qualities, data, link, currentItem) {
        if (!qualities || typeof qualities !== 'object' || Array.isArray(qualities)) return null;

        var currentUrls = {};
        var expected = streamUrl(link) || streamUrl(data);
        if (expected) currentUrls[expected] = true;

        var current = currentItem || (data && currentPlaylistItem(data, link));
        if (current) {
            addQualityUrls(currentUrls, current.quality);
            addQualityUrls(currentUrls, current.qualitys);
            addQualityUrls(currentUrls, current.qualities);
            itemQualities(current).forEach(function (entry) {
                if (entry && entry.url) currentUrls[entry.url] = true;
            });
        }

        var compact = {};
        var ordinaryCount = 0;
        Object.keys(qualities).forEach(function (label) {
            var value = qualities[label];
            var url = streamUrl(value);
            if (!url) return;

            if (isBridgeLabel(label)) {
                compact[label] = url;
                return;
            }

            if (currentUrls[url] && ordinaryCount < MAX_FORWARD_QUALITY_ENTRIES) {
                compact[label] = url;
                ordinaryCount += 1;
            }
        });
        return compact;
    }

    function createTransportBundle() {
        var urls = [];
        var indexes = Object.create(null);
        return {
            urls: urls,
            ref: function (url) {
                if (!url) return null;
                var index = indexes[url];
                if (index == null) {
                    index = urls.length;
                    indexes[url] = index;
                    urls.push(url);
                }
                return TRANSPORT_REF_PREFIX + index;
            },
            encoded: function () {
                if (!urls.length) return null;
                return TRANSPORT_BUNDLE_PREFIX + lzCompressUri(JSON.stringify(urls));
            }
        };
    }

    /**
     * Replace every full address in one quality map with a short local reference. The one
     * compressed bundle is put on the current playlist item, because that is the only map
     * Lampa's generic configurePlayerIntent reads for an external player.
     */
    function packQualityMap(qualities, transport, includeBundle) {
        if (!qualities || typeof qualities !== 'object' || Array.isArray(qualities)) return null;
        var packed = {};
        Object.keys(qualities).forEach(function (label) {
            if (label.indexOf(BUNDLE_PREFIX) === 0) return;
            var url = streamUrl(qualities[label]);
            if (!url || !/^https?:\/\//i.test(url)) return;
            // A non-current call-style episode carries its resolver endpoint as the
            // value. It is already compact and must stay a normal HTTP URL: the Android
            // side uses the same value to resolve the episode after selection. Putting
            // these endpoints into the LZ table only adds overhead and defeats the
            // transaction-size fix.
            if (labelResolverUrl(label) === url) {
                packed[label] = url;
                return;
            }
            // Keep the ordinary qualities direct. They are few (the current item's 4K/1080p
            // choices), remain useful to older players, and make the transport transparent.
            // The large bridge-generated episode/voice/reserve graph is what belongs in the
            // compressed table.
            if (!isBridgeLabel(label)) {
                packed[label] = url;
                return;
            }
            var ref = transport.ref(url);
            if (ref) packed[label] = ref;
        });
        if (includeBundle) {
            var bundle = transport.encoded();
            if (bundle) packed[BUNDLE_PREFIX] = bundle;
        }
        return Object.keys(packed).length ? packed : null;
    }

    function compactPlaylistItem(item, data, link) {
        if (!item || typeof item !== 'object') return null;
        var compact = {};
        [
            'season', 'episode', 'title', 'voice_name', 'voice', 'voiceover',
            'translation', 'dubbing', 'quality_label', 'stream', 'src', 'file',
            'link', 'path', 'url', 'timeline', 'quality'
        ].forEach(function (name) {
            if (item[name] != null) compact[name] = item[name];
        });

        // Native Lampa requires `url` for every playlist item.  MODS has used `stream` and
        // `src` for the same value, so normalise that one field instead of forwarding a
        // malformed current item and forcing Lampa into its single-item fallback path.
        if (!compact.url) {
            var direct = streamUrl(item);
            if (direct) compact.url = direct;
        }

        var rawQuality = item.quality || item.qualitys || item.qualities;
        var quality = compactQualityMap(rawQuality, data || item, link, item);
        if (!quality || !Object.keys(quality).length) {
            quality = {};
            itemQualities(item).forEach(function (entry) {
                if (entry && entry.url && Object.keys(quality).length < MAX_FORWARD_QUALITY_ENTRIES) {
                    quality[entry.label || 'Auto'] = entry.url;
                }
            });
        }
        if (Object.keys(quality).length) compact.quality = quality;
        return Object.keys(compact).length ? compact : null;
    }

    function currentPlaylistItem(data, link) {
        var playlist = Array.isArray(data.playlist) ? data.playlist : [];
        var expectedUrl = streamUrl(link) || streamUrl(data);
        for (var index = 0; index < playlist.length; index += 1) {
            if (ownsStreamUrl(playlist[index], expectedUrl)) return playlist[index];
        }

        var season = integer(data.season, -1);
        var episode = integer(data.episode, -1);
        for (var i = 0; i < playlist.length; i += 1) {
            var item = playlist[i];
            if (item && integer(item.season, -2) === season && integer(item.episode, -2) === episode) {
                return item;
            }
        }
        return null;
    }

    function compactForwardPayload(data, link) {
        var compact = {};
        [
            'url', 'title', 'movie_title', 'source_name', 'provider_name',
            'balancer_name', 'source', 'provider', 'balancer', 'online',
            'season', 'episode', 'voice_name', 'position'
        ].forEach(function (name) {
            if (data[name] != null && typeof data[name] !== 'object') compact[name] = data[name];
        });

        if (data.headers && typeof data.headers === 'object' && !Array.isArray(data.headers)) {
            compact.headers = data.headers;
        }
        var topQuality = data.quality && typeof data.quality === 'object' && !Array.isArray(data.quality)
            ? compactQualityMap(data.quality, data, link)
            : null;
        ['url_reserve', 'timeline'].forEach(function (name) {
            if (data[name] != null) compact[name] = data[name];
        });

        var current = compactPlaylistItem(currentPlaylistItem(data, link), data, link);
        var transport = createTransportBundle();
        if (topQuality) compact.quality = packQualityMap(topQuality, transport, false);
        if (current) {
            current.quality = packQualityMap(current.quality, transport, true) || current.quality;
            compact.playlist = [current];
        } else if (compact.quality) {
            // A single-item source has no playlist item for Lampa to inspect, so the bundle
            // must live beside its top-level quality map.
            compact.quality[BUNDLE_PREFIX] = transport.encoded();
        }
        return compact;
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

        var current = currentPlaylistItem(data, link);
        if (!current) return 0;

        var expectedUrl = streamUrl(link) || streamUrl(data);
        var merged = Object.assign({}, current.quality || {});
        Object.keys(merged).forEach(function (label) {
            if (isBridgeLabel(label)) delete merged[label];
        });
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

    function prepareForwardData(data, link) {
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
        return stats;
    }

    // Some MODS builds bypass both JavaScript hooks and construct the native JSON directly
    // from the object handed to Lampa.Player.play.  JSON.stringify honours an object's
    // non-enumerable toJSON method, so attach a lazy compact representation while leaving the
    // source object itself intact for Lampa's own UI and episode bookkeeping.
    function attachPayloadSerializer(data) {
        if (!data || typeof data !== 'object' || data.__vibePayloadSerializer === BRIDGE_VERSION) return;
        try {
            Object.defineProperty(data, '__vibePayloadSerializer', {
                value: BRIDGE_VERSION,
                configurable: true,
                enumerable: false
            });
            Object.defineProperty(data, 'toJSON', {
                configurable: true,
                enumerable: false,
                value: function () {
                    var link = streamUrl(this);
                    prepareForwardData(this, link);
                    return compactForwardPayload(this, link);
                }
            });
        } catch (error) {
            // Frozen source objects are still handled by the AndroidJS/Lampa hooks.
        }
    }

    var jsonStringifyOriginal = null;
    var jsonStringifyCompacting = false;

    function looksLikePlaybackPayload(value) {
        return value && typeof value === 'object' && !Array.isArray(value) &&
            (Array.isArray(value.playlist) ||
                (value.quality && typeof value.quality === 'object' && !Array.isArray(value.quality))) &&
            Boolean(streamUrl(value) || value.url);
    }

    // A source is allowed to call JSON.stringify itself, without touching Lampa.Android or
    // Lampa.Player first.  Intercept only objects that unmistakably look like playback data;
    // all other application JSON continues through the native implementation unchanged.
    function hookJsonStringify() {
        if (!window.JSON || typeof window.JSON.stringify !== 'function') return false;
        if (window.JSON.stringify.__vibeWrapped === BRIDGE_VERSION) return true;
        var original = window.JSON.stringify.__vibeOriginal || window.JSON.stringify;
        var wrapped = function (value, replacer, space) {
            if (jsonStringifyCompacting || !looksLikePlaybackPayload(value)) {
                return original.call(this, value, replacer, space);
            }
            jsonStringifyCompacting = true;
            try {
                var link = streamUrl(value);
                prepareForwardData(value, link);
                return original.call(this, compactForwardPayload(value, link), replacer, space);
            } finally {
                jsonStringifyCompacting = false;
            }
        };
        wrapped.__vibeOriginal = original;
        wrapped.__vibeWrapped = BRIDGE_VERSION;
        try {
            window.JSON.stringify = wrapped;
        } catch (error) {
            return false;
        }
        return window.JSON.stringify === wrapped;
    }

    // Describes the whole card and stays true for every entry inside it.
    var SESSION_FIELDS = [
        'title', 'movie_title', 'source_name', 'provider_name', 'balancer_name',
        'source', 'provider', 'balancer', 'online', 'voiceovers', 'translate', 'playlist',
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

    function voiceoverName(item, index, fallback) {
        if (!item || typeof item !== 'object') return 'Voiceover ' + (index + 1);
        return itemVoiceName(item, fallback) ||
            plainText(item.label) ||
            plainText(item.title) ||
            plainText(item.name) ||
            plainText(item.language) ||
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
            encodeURIComponent(itemVoiceName(item) || ''),
            // Lampa identifies an episode in its timeline by this hash. The player reports
            // progress against it for episodes chosen after launch.
            encodeURIComponent(nonEmptyString(timeline.hash) || ''),
            // Where this episode's per-quality addresses can be asked for. The source keeps
            // one default address per episode and hands out the rest only on request, which
            // is what Lampa does when the viewer picks an episode.
            encodeURIComponent(episodeResolverUrl(item) || '')
        ];
        return EPISODE_PREFIX + fields.join('|');
    }

    // MODS' newer episode object keeps the resolver in `url` and the already resolved
    // media address in `stream`, but no longer sets `method: 'call'`. Older builds did
    // set that method. Treat both shapes as a resolver pair. A URL that is itself a
    // media file remains a direct transport value; a different non-media URL is the
    // short endpoint VibePlayer can ask for after the viewer picks that episode.
    function episodeResolverUrl(item) {
        if (!item || typeof item !== 'object') return null;
        var candidate = nonEmptyString(item.url);
        if (!candidate) return null;
        if (item.method === 'call') return candidate;

        var resolved = itemStream(item);
        if (!resolved || resolved === candidate) return null;
        // Do not misclassify a second media URL as an API endpoint. MODS' resolver
        // addresses are ordinary HTTP URLs without a media suffix.
        if (/\.(?:m3u8|mp4|mkv|webm|avi|mov|mpd)(?:[?#]|$)/i.test(candidate)) return null;
        return candidate;
    }

    function episodeTransportValue(item, entry, current) {
        var resolver = episodeResolverUrl(item);
        if (!current && resolver) return resolver;
        return entry && nonEmptyString(entry.url);
    }

    function labelResolverUrl(label) {
        if (typeof label !== 'string' || label.indexOf(EPISODE_PREFIX) !== 0) return null;
        // Keep this parser local and forgiving; labels are ours but may have been
        // emitted by an older bridge and then handed back to us on a second open.
        var raw = label.slice(EPISODE_PREFIX.length).split('|');
        if (raw.length < 9 || !raw[8]) return null;
        try {
            return decodeURIComponent(raw[8]) || null;
        } catch (error) {
            return null;
        }
    }

    /**
     * Every address an episode has, by quality name.
     *
     * A source keeps its per-quality addresses beside the default one, in `qualitys`. Serialising
     * only the default leaves an episode chosen inside the player with a single address and no
     * quality to choose - which is exactly what Lampa itself sends six of when it launches that
     * same episode.
     */
    function itemQualities(item) {
        var qualities = item && (item.qualitys || item.qualities);
        if (!qualities && item && item.quality && typeof item.quality === 'object' && !Array.isArray(item.quality)) {
            qualities = item.quality;
        }
        var found = [];

        if (Array.isArray(qualities)) {
            qualities.forEach(function (entry) {
                var url = streamUrl(entry);
                var label = plainText(entry && (entry.label || entry.quality || entry.name));
                if (url) found.push({ label: label || 'Auto', url: url });
            });
        } else if (qualities && typeof qualities === 'object') {
            Object.keys(qualities).forEach(function (label) {
                if (isBridgeLabel(label)) return;
                var url = streamUrl(qualities[label]);
                if (url) found.push({ label: plainText(label) || 'Auto', url: url });
            });
        }

        if (found.length) return found;
        var single = itemStream(item);
        return single ? [{ label: plainText(item && item.quality_label) || 'Auto', url: single }] : [];
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
        var seenEpisodes = {};
        var serialized = 0;
        var currentSeason = integer(data && data.season, -1);
        var currentEpisode = integer(data && data.episode, -1);
        var currentVoice = itemVoiceName(data) || plainText(data && data.voice_name) || '';

        // The object can be handed to openPlayer more than once, and an older bridge may
        // already have left episode labels in it.  Keep the source's real qualities, but
        // rebuild our transport labels from the current capture so a malformed old label
        // cannot survive and crash the Android menu.
        Object.keys(qualities).forEach(function (label) {
            if (label.indexOf(EPISODE_PREFIX) === 0) delete qualities[label];
        });

        items.forEach(function (item) {
            var number = episodeNumber(item);
            if (!number) return;
            var voice = itemVoiceName(item) || '';
            var base = voice + '|' + integer(item.season, 0) + 'x' + number;
            seenEpisodes[base] = true;

            // Only the episode currently being launched needs every quality. For all other
            // episodes one direct address is enough; selecting one later asks the source's
            // advertised resolve endpoint for its remaining qualities. Keeping every quality
            // for every episode is what turns a normal series into an 800-KB Intent.
            var isCurrent = number === currentEpisode &&
                (currentSeason < 0 || integer(item.season, -1) === currentSeason) &&
                (!currentVoice || !voice || voice === currentVoice);
            var variants = isCurrent ? itemQualities(item) : itemQualities(item).slice(0, 1);
            variants.forEach(function (entry) {
                var key = base + '|' + entry.label;
                if (seen[key]) return;
                seen[key] = true;
                qualities[episodeLabel(item, entry.label)] = episodeTransportValue(item, entry, isCurrent);
                serialized += 1;
            });
        });

        // Whatever the payload already carried stays authoritative for entries we missed.
        playlist.forEach(function (item) {
            if (!item || typeof item !== 'object') return;
            var number = episodeNumber(item);
            var voice = itemVoiceName(item) || '';
            var base = voice + '|' + integer(item.season, 0) + 'x' + number;
            if (!number || seenEpisodes[base]) return;
            seenEpisodes[base] = true;
            var variants = item.quality;
            if (variants && typeof variants === 'object' && !Array.isArray(variants)) {
                var labels = Object.keys(variants).filter(function (quality) {
                    if (isBridgeLabel(quality)) return;
                    return Boolean(streamUrl(variants[quality]));
                });
                var isCurrent = number === currentEpisode &&
                    (currentSeason < 0 || integer(item.season, -1) === currentSeason) &&
                    (!currentVoice || !voice || voice === currentVoice);
                (isCurrent ? labels : labels.slice(0, 1)).forEach(function (quality) {
                    var entry = { url: streamUrl(variants[quality]) };
                    qualities[episodeLabel(item, quality)] = episodeTransportValue(item, entry, isCurrent);
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
            label.indexOf(RESERVE_PREFIX) === 0 ||
            label.indexOf(BUNDLE_PREFIX) === 0;
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

    function voiceoverContainerKey(key) {
        return /^(folder|data|result|items?|playlist|qualitys?|qualities|streams?|sources?|files?|episodes?|seasons?|voiceovers?|voices|translate|translations?|dubs?|flow|flows)$/i.test(key) ||
            /^\d+$/.test(key);
    }

    function voiceMapContainerKey(key) {
        return /^(folder|voiceovers?|voices|translate|translations?|dubs?|flow|flows)$/i.test(key);
    }

    /**
     * MODS has shipped voice choices as arrays, but the newer component keeps them in maps
     * such as translate["ColdFilm"] or flows["Original"].  Flatten only those already-loaded
     * objects.  No network call is made here; a playable object is just remembered for the
     * same Intent serialisation used by the older payload shape.
     */
    function appendArrayValues(target, value, fallback, depth, seen, voiceMap) {
        depth = depth || 0;
        if (!value || typeof value !== 'object' || depth > 6) return;
        voiceMap = Boolean(voiceMap);
        if (!seen) seen = [];
        if (seen.indexOf(value) !== -1) return;
        seen.push(value);

        if (itemHasPlayableAddress(value)) {
            target.push({ item: value, fallback: fallback });
            return;
        }
        if (Array.isArray(value)) {
            value.forEach(function (item) {
                appendArrayValues(target, item, fallback, depth + 1, seen, voiceMap);
            });
            return;
        }

        Object.keys(value).forEach(function (key) {
            var child = value[key];
            if (!child || typeof child !== 'object') return;
            var nextFallback = fallback;
            var nextVoiceMap = voiceMap || voiceMapContainerKey(key);
            // Unknown keys directly below a voice map are names. Keep an existing
            // explicit/folder voice ahead of a generic map key.
            if (voiceMap && !voiceoverContainerKey(key)) nextFallback = fallback || plainText(key);
            appendArrayValues(target, child, nextFallback, depth + 1, seen, nextVoiceMap);
        });
    }

    function addVoiceoverItem(target, item, index, fallback) {
        if (!item || typeof item !== 'object') return 0;
        var name = voiceoverName(item, index, fallback);
        var variants = item.quality || item.qualitys || item.qualities ||
            item.files || item.streams || item.sources;
        var added = 0;

        if (Array.isArray(variants)) {
            variants.forEach(function (entry) {
                var label = plainText(entry && (entry.label || entry.quality || entry.name)) || 'Auto';
                added += addVariant(target, name, label, entry);
            });
        } else if (variants && typeof variants === 'object') {
            Object.keys(variants).forEach(function (quality) {
                if (isBridgeLabel(quality)) return;
                added += addVariant(target, name, quality, variants[quality]);
            });
        } else {
            added += addVariant(
                target,
                name,
                item.quality_label || item.resolution || 'Auto',
                item,
            );
        }
        return added;
    }

    function serializeVoiceovers(data) {
        var voiceovers = [];
        appendArrayValues(voiceovers, data.voiceovers, null, 0, null, true);
        appendArrayValues(voiceovers, data.translate, null, 0, null, true);
        appendArrayValues(voiceovers, data.translations, null, 0, null, true);
        appendArrayValues(voiceovers, data.dubs, null, 0, null, true);

        var qualities = Object.assign({}, data.quality || {});
        Object.keys(qualities).forEach(function (label) {
            if (label.indexOf(LABEL_PREFIX) === 0) delete qualities[label];
        });
        var serialized = 0;

        voiceovers.forEach(function (entry, index) {
            serialized += addVoiceoverItem(qualities, entry.item, index, entry.fallback);
        });

        // A series source normally keeps the alternatives in folder[voice][season] rather
        // than in data.voiceovers.  Include only the episode being launched; serialising every
        // episode as a standalone voice would mix sources and make the menu lie.
        var currentSeason = integer(data && data.season, -1);
        var currentEpisode = integer(data && data.episode, -1);
        var sourceVoiceovers = 0;
        if (currentEpisode > 0) {
            allSourceItems().forEach(function (item) {
                var name = itemVoiceName(item);
                if (!name || integer(item && item.episode, -1) !== currentEpisode) return;
                if (currentSeason >= 0 && integer(item && item.season, -1) !== currentSeason) return;
                sourceVoiceovers += 1;
                serialized += addVoiceoverItem(qualities, item, sourceVoiceovers, name);
            });
        }

        if (serialized) data.quality = qualities;

        // Deliberately log structure counts only. Stream URLs and authorization data must never
        // appear in WebView/ADB logs.
        console.info(
            '[VibePlayer] voiceovers=' + voiceovers.length +
            ' source=' + sourceVoiceovers +
            ' serialized=' + serialized,
        );
        return { total: voiceovers.length + sourceVoiceovers, serialized: serialized };
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
    var folderCard = null;
    // Folder voice names are the only context that is not repeated on every episode object.
    // Keep that context out of the source's own objects: mutating them changes what Lampa's
    // built-in player sees and makes a diagnostic bridge an accidental source plugin.
    var sourceItemVoices = typeof WeakMap === 'function' ? new WeakMap() : null;

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
        // A resolved address wins.  `url` on a call-style item is an API endpoint, not media;
        // the quality map is handled separately by itemQualities below.
        return nonEmptyString(item.stream) ||
            nonEmptyString(item.src) ||
            nonEmptyString(item.file) ||
            nonEmptyString(item.link) ||
            nonEmptyString(item.path) ||
            (item.method === 'call' ? null : nonEmptyString(item.url));
    }

    function itemHasPlayableAddress(item) {
        return Boolean(itemStream(item) || itemQualities(item).length);
    }

    function rememberSourceVoice(item, fallback) {
        var voice = plainText(fallback);
        if (!sourceItemVoices || !item || typeof item !== 'object' || !voice) return;
        if (!explicitVoiceName(item)) sourceItemVoices.set(item, voice);
    }

    function rememberItem(item, fallback) {
        if (!item || typeof item !== 'object' || !itemHasPlayableAddress(item)) return;
        rememberSourceVoice(item, fallback);
        if (sourceItems.length >= MAX_SOURCE_ITEMS) return;
        if (sourceItems.indexOf(item) === -1) sourceItems.push(item);
    }

    /**
     * Observe values a source component has already produced.  The newer MODS component no
     * longer exposes parse()/toPlayElement(); it keeps the resolved entries in return values,
     * flow maps and component data instead.  This collector is deliberately bounded and
     * read-only: it never invokes a function, follows a promise or performs a request.
     */
    function collectSourceValue(value, depth, fallback, seen, voiceMap) {
        depth = depth || 0;
        if (!value || typeof value !== 'object' || depth > 6) return;
        voiceMap = Boolean(voiceMap);
        if (!seen) seen = typeof WeakSet === 'function' ? new WeakSet() : [];

        if (typeof seen.has === 'function') {
            if (seen.has(value)) return;
            seen.add(value);
        } else {
            if (seen.indexOf(value) !== -1) return;
            seen.push(value);
        }

        rememberFolder(value);
        if (itemHasPlayableAddress(value)) {
            rememberItem(value, fallback);
            return;
        }

        if (Array.isArray(value)) {
            value.forEach(function (entry) {
                collectSourceValue(entry, depth + 1, fallback, seen, voiceMap);
            });
            return;
        }

        Object.keys(value).slice(0, 80).forEach(function (key) {
            var child = value[key];
            if (!child || typeof child !== 'object') return;
            var nextFallback = fallback;
            var nextVoiceMap = voiceMap || voiceMapContainerKey(key);
            // Unknown keys below a voice map are normally voice names
            // (translate["Dub"], flows["Original"], folder["ColdFilm"], ...).
            if (voiceMap && !voiceoverContainerKey(key)) nextFallback = fallback || plainText(key);
            collectSourceValue(child, depth + 1, nextFallback, seen, nextVoiceMap);
        });
    }

    // A few source versions keep the episode as a call-style object (`url` is an API method)
    // and return its media address as a string from getFileUrl/getExternalPlayUrl.  Preserve
    // that already-resolved result on a shallow copy, without mutating MODS's own item.
    function rememberResolvedResult(method, result, args) {
        if (!/^(getExternalPlayUrl|normalizeExternalPlayFile|getFileUrl|fetchFileUrl)$/.test(method)) return;
        var resolved = nonEmptyString(result);
        if (!resolved || !/^https?:\/\//i.test(resolved)) return;

        Array.prototype.slice.call(args).forEach(function (value) {
            if (!value || typeof value !== 'object' || Array.isArray(value)) return;
            var hasEpisode = episodeNumber(value) || value.season != null || value.episode != null;
            if (!hasEpisode && !explicitVoiceName(value) && !itemQualities(value).length) return;
            var copy = Object.assign({}, value);
            copy.stream = resolved;
            rememberItem(copy, itemVoiceName(value));
        });
    }

    function observeSourceResult(method, result, args) {
        collectSourceValue(result, 0, null);
        Array.prototype.slice.call(args).forEach(function (value) {
            collectSourceValue(value, 0, null);
        });
        rememberResolvedResult(method, result, args);

        // MODS may return a promise that it has already started because of the user's normal
        // UI action. Attach a passive continuation so the resolved object is captured too;
        // this continuation does not create or retry the underlying request.
        if (result && typeof result.then === 'function') {
            result.then(function (resolved) {
                collectSourceValue(resolved, 0, null);
                rememberResolvedResult(method, resolved, args);
            }, function () { /* source's own failure */ });
        }
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

    function wrapComponentMethodAfter(component, name, observer) {
        var current = component[name];
        if (typeof current !== 'function') return false;
        if (current.__vibeWrapped === BRIDGE_VERSION) return false;
        var original = current.__vibeOriginal || current;
        var wrapped = function () {
            var result = original.apply(this, arguments);
            hookHits[name] = (hookHits[name] || 0) + 1;
            try { observer(result, arguments); } catch (error) { /* observation only */ }
            return result;
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
        var folder = value && typeof value === 'object'
            ? value.folder || (value.data && value.data.folder) || (value.result && value.result.folder)
            : null;
        if (!folder || typeof folder !== 'object') return;

        // Answers arrive in parts - one season, one voice - and the component is rebuilt
        // between them, so its identity cannot say whether an answer continues the current
        // source. The card can: while it is the same card, parts belong together.
        var movie = activeMovie();
        var card = movie && (movie.id || movie.original_title || movie.title) || null;
        if (card !== folderCard) {
            folderCard = card;
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
            // Some versions keep the resolved catalogue on the component instead of passing
            // it through parse(). Read only the known data slots; never invoke component code.
            [
                'data', 'folder', 'result', 'items', 'playlist', 'sources', 'flows', 'flow',
                'voiceovers', 'voices', 'translate', 'translations', 'dubs', 'qualities'
            ].forEach(function (key) {
                if (component[key] && typeof component[key] === 'object') {
                    collectSourceValue(component[key], 0, null, null, voiceMapContainerKey(key));
                }
            });

            wrapComponentMethod(component, 'parse', rememberFolder);
            wrapComponentMethod(component, 'toPlayElement', rememberItem);

            // The current MODS methods resolve or reshape source entries synchronously or return
            // a promise started by the user's UI action. Observe both arguments and return
            // values after the original method has run, without changing its behaviour.
            [
                'build', 'startSource', 'lifeSource', 'createSource', 'create', 'request',
                'setFlowsForQuality', 'setFlowsForItem', 'getExternalPlayUrl',
                'normalizeExternalPlayFile', 'getFileUrl', 'fetchFileUrl', 'changeQuality',
                'applyPlayerDisplayTitle'
            ].forEach(function (name) {
                wrapComponentMethodAfter(component, name, function (result, args) {
                    observeSourceResult(name, result, args);
                });
            });
        });
    }

    /** Every item the balancer supplied, flattened out of folder[voice][season]. */
    function folderItems() {
        var folder = sourceFolder;
        if (!folder || typeof folder !== 'object') return [];
        var items = [];

        function addItem(item, voice) {
            if (!item || typeof item !== 'object' || !itemHasPlayableAddress(item)) return;
            rememberSourceVoice(item, voice);
            if (items.length < MAX_SOURCE_ITEMS && items.indexOf(item) === -1) items.push(item);
        }

        function isStructuralKey(key) {
            return /^(folder|data|result|items?|playlist|qualities?|streams?|episodes?|seasons?)$/i.test(key) ||
                /^\d+$/.test(key);
        }

        function collect(value, depth, voice) {
            if (items.length >= MAX_SOURCE_ITEMS || !value || typeof value !== 'object') return;
            if (!Array.isArray(value) && episodeNumber(value) && itemHasPlayableAddress(value)) {
                addItem(value, voice);
                return;
            }
            if (Array.isArray(value)) {
                value.forEach(function (entry) {
                    addItem(entry, voice);
                });
                return;
            }
            if (depth > 5) return;
            Object.keys(value).forEach(function (key) {
                // The first non-structural level of the folder is the voice name itself.
                var nextVoice = voice || (depth === 0 && !isStructuralKey(key) ? plainText(key) : null);
                collect(value[key], depth + 1, nextVoice);
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
            var voice = itemVoiceName(item);
            if (voice && voices.indexOf(voice) === -1) voices.push(voice);
            var season = integer(item.season, -1);
            if (season >= 0 && seasons.indexOf(season) === -1) seasons.push(season);
        });
        return {
            items: items.length,
            withStream: items.filter(itemHasPlayableAddress).length,
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
        if (!Lampa || !Lampa.Player || typeof Lampa.Player.play !== 'function') return false;
        if (Lampa.Player.play.__vibeWrapped === BRIDGE_VERSION) return false;
        var original = Lampa.Player.play.__vibeOriginal || Lampa.Player.play;
        if (!original) return;

        var wrapped = function (data) {
            if (data && typeof data === 'object') {
                capturedPlayback = data;
                attachPayloadSerializer(data);
                window.VibePlayerBridge.lastCapture = captureSummary(data);
                console.info(
                    '[VibePlayer] captured fields=' + window.VibePlayerBridge.lastCapture.fields.join(',') +
                    ' headers=' + window.VibePlayerBridge.lastCapture.headerNames.join(',')
                );
            }
            return original.apply(this, arguments);
        };
        wrapped.__vibeOriginal = original;
        wrapped.__vibeWrapped = BRIDGE_VERSION;
        Lampa.Player.play = wrapped;
        return true;
    }

    function forwardOpenPlayer(original, receiver, link, payload) {
        var data = decodePayload(payload);
        prepareForwardData(data, link);
        // Never forward the full captured playlist/voiceover graph through Binder. It can
        // exceed Android's transaction limit before the external player process is even
        // created. The compact payload retains the current item and all transport labels.
        var forwarded = data ? compactForwardPayload(data, link) : data;
        if (forwarded) {
            console.info('[VibePlayer] forwarded payload bytes=' + JSON.stringify(forwarded).length);
        }
        return original.call(receiver, link, data ? encodePayload(payload, forwarded) : payload);
    }

    function wrapOpenPlayerTarget(holder, property) {
        if (!holder || typeof holder[property] !== 'function') return false;
        var current = holder[property];
        if (current.__vibeOpenPlayerWrapped === BRIDGE_VERSION) return false;
        var original = current.__vibeOriginal || current;
        var wrapped = function (link, payload) {
            return forwardOpenPlayer(original, this, link, payload);
        };
        wrapped.__vibeOriginal = original;
        wrapped.__vibeOpenPlayerWrapped = BRIDGE_VERSION;
        try {
            holder[property] = wrapped;
        } catch (error) {
            return false;
        }
        return holder[property] === wrapped;
    }

    var androidJsProxyTarget = null;
    var androidJsProxy = null;

    // WebView normally lets an injected Java method be shadowed by assignment.  A few
    // Android 9 WebView builds expose it as a read-only host object instead.  In that case
    // install a transparent Proxy around the global object so direct `AndroidJS.openPlayer`
    // calls still pass through the bridge.  The original object remains the receiver for
    // every other native method.
    function hookAndroidJsProxy() {
        var nativeAndroidJs = window.AndroidJS;
        if (!nativeAndroidJs || typeof nativeAndroidJs.openPlayer !== 'function') return false;
        if (androidJsProxy && nativeAndroidJs === androidJsProxy) return true;
        if (typeof Proxy !== 'function') return false;

        var original = nativeAndroidJs.openPlayer.__vibeOriginal || nativeAndroidJs.openPlayer;
        var wrapped = function (link, payload) {
            return forwardOpenPlayer(original, nativeAndroidJs, link, payload);
        };
        wrapped.__vibeOriginal = original;
        wrapped.__vibeOpenPlayerWrapped = BRIDGE_VERSION;

        try {
            var boundMethods = {};
            var proxy = new Proxy(nativeAndroidJs, {
                get: function (target, property, receiver) {
                    if (property === 'openPlayer') return wrapped;
                    var value = Reflect.get(target, property, target);
                    // Android's Java bridge validates the injected receiver.  Returning a
                    // method bound to the original object keeps storageChange/httpReq/etc.
                    // native while the facade remains transparent to Lampa's JavaScript.
                    if (typeof value === 'function') {
                        if (!boundMethods[property]) boundMethods[property] = value.bind(target);
                        return boundMethods[property];
                    }
                    return value;
                }
            });
            window.AndroidJS = proxy;
            androidJsProxyTarget = nativeAndroidJs;
            androidJsProxy = proxy;
            return window.AndroidJS === proxy;
        } catch (error) {
            return false;
        }
    }

    function hookOpenPlayer(Lampa) {
        var hooked = 0;
        var targets = [
            [window.AndroidJS, 'openPlayer'],
            [window.Android, 'openPlayer'],
            [Lampa && Lampa.AndroidJS, 'openPlayer'],
            [Lampa && Lampa.Android, 'openPlayer']
        ];
        targets.forEach(function (target) {
            if (wrapOpenPlayerTarget(target[0], target[1])) hooked += 1;
        });
        // If assignment to the injected object was rejected, the proxy is the fallback.
        if (window.AndroidJS && window.AndroidJS !== androidJsProxyTarget &&
            typeof window.AndroidJS.openPlayer === 'function' &&
            window.AndroidJS.openPlayer.__vibeOpenPlayerWrapped !== BRIDGE_VERSION) {
            if (hookAndroidJsProxy()) hooked += 1;
        }
        return hooked;
    }

    var componentWatchStarted = false;
    var openPlayerWatchStarted = false;
    var progressWatchStarted = false;
    var installLogged = false;

    function install() {
        var Lampa = window.Lampa;
        if (!Lampa && !window.AndroidJS && !window.Android) return false;

        hookJsonStringify();
        hookPlayerPlay(Lampa);
        var hooked = hookOpenPlayer(Lampa);
        if (Lampa) {
            hookSourceComponent();
            if (!componentWatchStarted && typeof setInterval === 'function') {
                componentWatchStarted = true;
                setInterval(hookSourceComponent, COMPONENT_WATCH_MS);
            }
        }
        if (!openPlayerWatchStarted && typeof setInterval === 'function') {
            openPlayerWatchStarted = true;
            setInterval(function () { hookOpenPlayer(window.Lampa); }, OPEN_PLAYER_WATCH_MS);
        }
        if (!progressWatchStarted) {
            progressWatchStarted = true;
            watchForPlayerProgress();
        }
        if (hooked || window.VibePlayerBridge.installed) {
            window.VibePlayerBridge.installed = true;
            if (!installLogged) {
                installLogged = true;
                console.info('[VibePlayer] bridge ' + BRIDGE_VERSION + ' installed');
            }
            return true;
        }
        return false;
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
