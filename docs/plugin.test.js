'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const pluginSource = fs.readFileSync(__dirname + '/VibePlayer-Lampa-Plugin.js', 'utf8');
const loaderSource = fs.readFileSync(__dirname + '/v.js', 'utf8');
const logs = [];
let forwardedPayload;
let directForwardedPayload;

const context = {
    console: {
        info: (message) => logs.push(String(message)),
        warn: (message) => logs.push(String(message))
    },
    window: {
        location: { origin: 'http://lampa.mx' },
        navigator: { userAgent: 'Lampa WebView Test', language: 'uk-UA' },
        JSON: { stringify: JSON.stringify, parse: JSON.parse }
    }
};
context.window.Lampa = {
    Player: {
        play: () => 'played'
    },
    Android: {
        openPlayer: (_link, payload) => {
            forwardedPayload = payload;
            return 'forwarded';
        }
    }
};
context.window.AndroidJS = {
    openPlayer: (_link, payload) => {
        directForwardedPayload = payload;
        return 'direct-forwarded';
    }
};
context.Lampa = context.window.Lampa;

vm.runInNewContext(pluginSource, context);

const captured = {
    title: 'The Series',
    source: 'Alloha',
    url: 'https://media.example/current.m3u8',
    url_reserve: 'https://backup.example/current.mp4',
    headers: {
        Cookie: 'source-provided-cookie',
        'X-Source-Header': 'source-provided-value'
    },
    quality: {
        '1080p': 'https://media.example/current.m3u8',
        '720p': 'https://media.example/current-720.m3u8',
        '@VIBEEPISODE@1|1|0|29|Old%20shape|': 'https://media.example/old-shape.m3u8'
    },
    quality_reserve: {
        '1080p': 'https://backup.example/current-1080.mp4',
        '720p': 'https://backup.example/current-720.mp4'
    },
    voiceovers: [{
        title: 'Dub',
        quality: {
            '720p': 'https://media.example/dub-720.m3u8'
        }
    }],
    // Current MODS calls this collection `translate`; it is not `voiceovers`.
    translate: [{
        title: 'ColdFilm',
        quality: {
            '720p': 'https://media.example/cold-720.m3u8'
        }
    }],
    playlist: [{
        season: 1,
        episode: 2,
        voice_name: 'Dub <span style="color:red">HD</span>',
        title: 'Second Episode',
        timeline: { percent: 94, time: 122 },
        quality: {
            '1080p': 'https://media.example/s01e02-1080.m3u8'
        }
    }]
};

assert.equal(context.Lampa.Player.play(captured), 'played');

// The last-resort path is the object itself: if a source later calls JSON.stringify(data)
// without going through an exported player function, toJSON must still emit the compact
// representation and keep the original object available to Lampa.
const playPayload = {
    url: 'https://media.example/play.m3u8',
    season: 1,
    episode: 1,
    quality: Object.assign(
        { '1080p': 'https://media.example/play.m3u8' },
        Object.fromEntries(Array.from({ length: 500 }, (_, index) => [
            'raw-' + index,
            'https://media.example/play/' + index + '-' + 'x'.repeat(1800) + '.m3u8'
        ])),
    ),
    playlist: [{
        season: 1,
        episode: 1,
        url: 'https://media.example/play.m3u8',
        quality: { '1080p': 'https://media.example/play.m3u8' }
    }]
};
assert.equal(context.Lampa.Player.play(playPayload), 'played');
const serializedPlayPayload = JSON.stringify(playPayload);
assert(serializedPlayPayload.length < 700000);
assert.equal(JSON.parse(serializedPlayPayload).quality['raw-0'], undefined);
assert.equal(playPayload.playlist.length, 1);
assert.equal(context.Lampa.Player.play(captured), 'played');

const payload = JSON.stringify({
    url: 'https://media.example/current.m3u8'
});

assert.equal(
    context.Lampa.Android.openPlayer('https://media.example/current.m3u8', payload),
    'forwarded'
);
assert.equal(typeof forwardedPayload, 'string');

const forwarded = JSON.parse(forwardedPayload);
const labels = Object.keys(forwarded.quality);
assert(labels.includes('1080p'));
assert(labels.some((label) => label.startsWith('@VIBEMETA@The%20Series|Alloha')));
assert(labels.some((label) => label.startsWith('@VIBEVOICE@Dub|720p')));
assert(labels.some((label) => label.startsWith('@VIBEVOICE@ColdFilm|720p')));
assert(labels.some((label) => label.startsWith('@VIBEEPISODE@1|2|94|122|Second%20Episode|1080p')));
assert(!labels.some((label) => label === '@VIBEEPISODE@1|1|0|29|Old%20shape|'));
// Markup from the page must never reach the player's overlay.
const episodeLabel = labels.find((label) => label.startsWith('@VIBEEPISODE@1|2|'));
assert.equal(episodeLabel.split('|')[6], 'Dub%20HD');

// Reserves ride along in source order, the one matching the playing quality first,
// and the primary address is never repeated as its own backup.
const reserves = labels
    .filter((label) => label.startsWith('@VIBERESERVE@'))
    .sort()
    .map((label) => forwarded.quality[label]);
assert.deepEqual(reserves, [
    'https://backup.example/current.mp4',
    'https://backup.example/current-1080.mp4',
    'https://backup.example/current-720.mp4'
]);
assert.equal(context.window.VibePlayerBridge.lastStats.reserves, 3);
assert(!reserves.includes('https://media.example/current.m3u8'));
assert.equal(forwarded.headers.Cookie, 'source-provided-cookie');
assert.equal(forwarded.headers['X-Source-Header'], 'source-provided-value');
assert.equal(forwarded.headers.Accept, '*/*');
assert.equal(forwarded.headers.Referer, 'http://lampa.mx/');
assert.equal(forwarded.headers.Origin, 'http://lampa.mx');
assert.equal(forwarded.headers['User-Agent'], 'Lampa WebView Test');
assert.equal(forwarded.headers['Accept-Language'], 'uk-UA');
// The bridge invents no header of its own. Nothing else sends one, and a source that screens
// requests has no reason to trust it - while headers the source itself supplied stay untouched.
assert.deepEqual(
    Object.keys(forwarded.headers).filter((name) => /^x-vibe/i.test(name)),
    [],
    'the bridge must not add headers of its own',
);
assert.equal(forwarded.headers['X-Source-Header'], 'source-provided-value');
assert.equal(context.window.VibePlayerBridge.version, '0.41.0');

assert.equal(context.window.VibePlayerBridge.lastStats.captured, true);
assert.equal(context.window.VibePlayerBridge.lastStats.headers, 7);
assert.deepEqual(Array.from(context.window.VibePlayerBridge.lastCapture.headerNames), ['Cookie', 'X-Source-Header']);
assert(!logs.join('\n').includes('media.example'));
const fetchTargets = [...pluginSource.matchAll(/fetch\s*\(\s*([A-Za-z_$][\w$]*)/g)].map((m) => m[1]);
assert.deepEqual([...new Set(fetchTargets)], ['PROGRESS_ENDPOINT'], 'fetch may only reach the player');
assert(/PROGRESS_ENDPOINT\s*=\s*'http:\/\/127\.0\.0\.1:/.test(pluginSource), 'loopback only');
assert(!/XMLHttpRequest|Lampa\.Reguest|Lampa\.Request/.test(pluginSource));
assert(loaderSource.includes('VibePlayer-Lampa-Plugin.js?v=0.41.0'));

// A direct source call may never touch Lampa.Player.play. The JSON hook still has to compact
// the object at the moment MODS serializes it for AndroidJS.
const directJsonPayload = {
    url: 'https://media.example/json.m3u8',
    quality: Object.assign(
        { '1080p': 'https://media.example/json.m3u8' },
        Object.fromEntries(Array.from({ length: 500 }, (_, index) => [
            'raw-' + index,
            'https://media.example/json/' + index + '-' + 'x'.repeat(1800) + '.m3u8'
        ])),
    ),
    playlist: [{
        season: 1,
        episode: 1,
        url: 'https://media.example/json.m3u8',
        quality: { '1080p': 'https://media.example/json.m3u8' }
    }]
};
const compactJson = context.window.JSON.parse(context.window.JSON.stringify(directJsonPayload));
assert(compactJson.playlist && compactJson.playlist.length === 1);
assert(compactJson.quality['raw-0'] === undefined);
assert(JSON.stringify(compactJson).length < 700000);

forwardedPayload = null;
assert.equal(
    context.Lampa.Android.openPlayer(
        'https://media.example/override.m3u8',
        JSON.stringify({
            url: 'https://media.example/override.m3u8',
            headers: {
                Origin: 'https://source.example',
                Referer: 'https://source.example/watch',
                'User-Agent': 'Source Agent',
                Accept: 'application/vnd.apple.mpegurl'
            }
        })
    ),
    'forwarded'
);
const overridden = JSON.parse(forwardedPayload);
assert.equal(overridden.headers.Origin, 'https://source.example');
assert.equal(overridden.headers.Referer, 'https://source.example/watch');
assert.equal(overridden.headers['User-Agent'], 'Source Agent');
assert.equal(overridden.headers.Accept, 'application/vnd.apple.mpegurl');

// Launching a sibling episode must not inherit the captured episode's streams. The capture
// above is episode 1; episode 2 lives in its playlist and owns exactly one quality.
forwardedPayload = null;
assert.equal(
    context.Lampa.Android.openPlayer(
        'https://media.example/s01e02-1080.m3u8',
        JSON.stringify({ url: 'https://media.example/s01e02-1080.m3u8' })
    ),
    'forwarded'
);
const episode = JSON.parse(forwardedPayload);
const episodeQualities = Object.keys(episode.quality).filter((label) => !label.startsWith('@VIBE'));
assert.deepEqual(episodeQualities, ['1080p']);
assert.equal(episode.quality['1080p'], 'https://media.example/s01e02-1080.m3u8');
assert.equal(Object.values(episode.quality).includes('https://media.example/current-720.m3u8'), false);
assert.equal(Object.values(episode.quality).includes('https://media.example/current.m3u8'), false);
// Backup addresses belong to episode 1, so episode 2 must ship none.
assert.equal(context.window.VibePlayerBridge.lastStats.reserves, 0);
assert.equal(episode.url_reserve, undefined);
// Card-level context still travels: the title and the playlist describe the whole series.
assert.equal(episode.title, 'The Series');
assert.equal(episode.playlist.length, 1);

// Lampa reads the current playlist entry's quality map, not the payload's top level, so
// the labels have to be present on that entry too.
const mirroredEntry = episode.playlist.find((item) => item.season === 1 && item.episode === 2);
const mirroredLabels = Object.keys(mirroredEntry.quality).filter((l) => l.startsWith('@VIBE'));
assert(mirroredLabels.length > 0, 'labels must be mirrored onto the launched playlist entry');
assert.equal(mirroredEntry.quality['1080p'], 'https://media.example/s01e02-1080.m3u8');

// A large series must stay below Android's Binder transaction limit. The bridge forwards
// only the current playlist item and one default URL per sibling episode.
const oversizedPlaylist = Array.from({ length: 400 }, (_, index) => ({
    season: 1,
    episode: index + 1,
    title: 'Episode ' + (index + 1),
    stream: 'https://media.example/series/s01e' + String(index + 1).padStart(2, '0') + '.m3u8',
    quality: {
        '2160p': 'https://media.example/series/s01e' + (index + 1) + '-2160.m3u8',
        '1080p': 'https://media.example/series/s01e' + (index + 1) + '-1080.m3u8'
    },
    poster: 'x'.repeat(1500)
}));
forwardedPayload = null;
context.Lampa.Android.openPlayer(
    'https://media.example/series/s01e200.m3u8',
    JSON.stringify({
        url: 'https://media.example/series/s01e200.m3u8',
        season: 1,
        episode: 200,
        playlist: oversizedPlaylist
    })
);
const bounded = JSON.parse(forwardedPayload);
assert(bounded.playlist && bounded.playlist.length === 1);
assert(JSON.stringify(bounded).length < 700000);

// A launch that belongs to no captured entry is enriched with nothing at all.
forwardedPayload = null;
context.Lampa.Android.openPlayer(
    'https://other.example/unrelated.m3u8',
    JSON.stringify({ url: 'https://other.example/unrelated.m3u8' })
);
const unrelated = JSON.parse(forwardedPayload);
assert.equal(context.window.VibePlayerBridge.lastStats.captured, false);
assert.equal(unrelated.title, undefined);
// Only the diagnostic label, carrying no title, no source and no stream of its own.
assert.deepEqual(Object.keys(unrelated.quality), ['@VIBEMETA@||c0p1v1f10n0s0w0|0|0||0.41.0']);

// The probe reports the capture structurally: matched, 1 playlist entry, 1 voiceover,
// 10 top-level fields (including the current MODS `translate` collection). It must never
// carry anything resembling a URL.
const probe = Object.keys(forwarded.quality)
    .find((label) => label.startsWith('@VIBEMETA@'))
    .split('|')[2];
assert.match(probe, /^c1p1v1f10n0s0w0$/);

// The bridge must report itself installed, otherwise it is silently doing nothing.
assert.equal(context.window.VibePlayerBridge.installed, true);

// MODS can bypass Lampa.Android and call the native AndroidJS interface directly. The bridge
// must compact that last hop too; otherwise the native Activity still receives the original
// oversized JSON even though the higher-level test above passes.
directForwardedPayload = null;
const directOversized = {
    url: 'https://media.example/direct.m3u8',
    season: 1,
    episode: 1,
    quality: Object.assign(
        { '1080p': 'https://media.example/direct.m3u8' },
        Object.fromEntries(Array.from({ length: 500 }, (_, index) => [
            'raw-' + index,
            'https://media.example/direct/' + index + '-' + 'x'.repeat(1800) + '.m3u8'
        ])),
    ),
    playlist: [{
        season: 1,
        episode: 1,
        url: 'https://media.example/direct.m3u8',
        quality: {
            '1080p': 'https://media.example/direct.m3u8',
            '720p': 'https://media.example/direct-720.m3u8'
        }
    }]
};
assert.equal(
    context.window.AndroidJS.openPlayer(
        directOversized.url,
        JSON.stringify(directOversized),
    ),
    'direct-forwarded',
);
assert.equal(typeof directForwardedPayload, 'string');
const directBounded = JSON.parse(directForwardedPayload);
assert(JSON.stringify(directBounded).length < 700000);
assert.equal(directBounded.quality['1080p'], directOversized.url);
assert.equal(directBounded.quality['raw-0'], undefined);

// Source components are where the current MODS payload keeps the other voices.  They are not
// necessarily present in the top-level `voiceovers` array, so exercise the passive folder hooks
// as well as the already-captured playback path.
let sourceForwardedPayload;
const sourceComponent = {
    parse: (value) => value,
    toPlayElement: (value) => value
};
const sourceContext = {
    console: { info: () => {}, warn: () => {} },
    window: {
        location: { origin: 'http://lampa.mx' },
        navigator: { userAgent: 'Lampa WebView Test', language: 'uk-UA' }
    }
};
sourceContext.window.Lampa = {
    Player: { play: () => 'played' },
    Android: {
        openPlayer: (_link, payload) => {
            sourceForwardedPayload = payload;
            return 'forwarded';
        }
    },
    Activity: {
        all: () => [{ activity: { component: sourceComponent } }],
        active: () => ({ movie: { id: 'series-1' } })
    }
};
sourceContext.Lampa = sourceContext.window.Lampa;
vm.runInNewContext(pluginSource, sourceContext);

sourceComponent.parse({
    folder: {
        'Dub Voice': { 1: [{ season: 1, episode: 1, title: 'First', stream: 'https://media.example/dub.m3u8', qualitys: { '1080p': 'https://media.example/dub-1080.m3u8' } }] },
        Original: { 1: [{ season: 1, episode: 1, title: 'First', stream: 'https://media.example/original.m3u8', qualitys: { '1080p': 'https://media.example/original-1080.m3u8' } }] }
    }
});
sourceContext.Lampa.Player.play({ url: 'https://media.example/dub.m3u8', season: 1, episode: 1, playlist: [] });
sourceContext.Lampa.Android.openPlayer(
    'https://media.example/dub.m3u8',
    JSON.stringify({ url: 'https://media.example/dub.m3u8', season: 1, episode: 1 })
);
const sourceOutput = JSON.parse(sourceForwardedPayload);
const sourceLabels = Object.keys(sourceOutput.quality);
assert(sourceLabels.some((label) => label.startsWith('@VIBEVOICE@Dub%20Voice|1080p')));
assert(sourceLabels.some((label) => label.startsWith('@VIBEVOICE@Original|1080p')));
assert(sourceLabels.some((label) => label.startsWith('@VIBEEPISODE@1|1|0|0|First|1080p|Dub%20Voice')));
assert(sourceLabels.some((label) => label.startsWith('@VIBEEPISODE@1|1|0|0|First|1080p|Original')));

// The current MODS component exposes no parse()/toPlayElement().  Its already-resolved voices
// arrive through setFlowsForItem as a map keyed by translation name.  The bridge must observe
// that value after the component method returns, without calling the source itself.
let modernForwardedPayload;
const modernSourceComponent = {
    setFlowsForItem: (value) => value,
    getFileUrl: (value) => value && value.method === 'call'
        ? 'https://media.example/modern-call.m3u8'
        : value,
    fetchFileUrl: () => ({
        then: (resolve) => resolve('https://media.example/modern-async.m3u8')
    })
};
const modernContext = {
    console: { info: () => {}, warn: () => {} },
    window: {
        location: { origin: 'http://lampa.mx' },
        navigator: { userAgent: 'Lampa WebView Test', language: 'uk-UA' }
    }
};
modernContext.window.Lampa = {
    Player: { play: () => 'played' },
    Android: {
        openPlayer: (_link, payload) => {
            modernForwardedPayload = payload;
            return 'forwarded';
        }
    },
    Activity: {
        all: () => [{ activity: { component: modernSourceComponent } }],
        active: () => ({ movie: { id: 'series-modern' } })
    }
};
modernContext.Lampa = modernContext.window.Lampa;
vm.runInNewContext(pluginSource, modernContext);

modernSourceComponent.setFlowsForItem({
    translate: {
        'Dub Voice': {
            season: 1,
            episode: 1,
            title: 'First',
            quality: { '1080p': 'https://media.example/modern-dub.m3u8' }
        },
        Original: {
            season: 1,
            episode: 1,
            title: 'First',
            quality: { '1080p': 'https://media.example/modern-original.m3u8' }
        }
    }
});
modernSourceComponent.getFileUrl({
    method: 'call',
    url: 'https://mods.example/resolve',
    voice_name: 'Call Voice',
    season: 1,
    episode: 1,
    title: 'First'
});
modernSourceComponent.fetchFileUrl({
    method: 'call',
    url: 'https://mods.example/resolve-async',
    voice_name: 'Async Voice',
    season: 1,
    episode: 1,
    title: 'First'
});
modernContext.Lampa.Player.play({ url: 'https://media.example/modern-dub.m3u8', season: 1, episode: 1, playlist: [] });
modernContext.Lampa.Android.openPlayer(
    'https://media.example/modern-dub.m3u8',
    JSON.stringify({ url: 'https://media.example/modern-dub.m3u8', season: 1, episode: 1 })
);
const modernOutput = JSON.parse(modernForwardedPayload);
const modernLabels = Object.keys(modernOutput.quality);
assert(modernLabels.some((label) => label.startsWith('@VIBEVOICE@Dub%20Voice|1080p')));
assert(modernLabels.some((label) => label.startsWith('@VIBEVOICE@Original|1080p')));
assert(modernLabels.some((label) => label.startsWith('@VIBEVOICE@Call%20Voice|Auto')));
assert(modernLabels.some((label) => label.startsWith('@VIBEVOICE@Async%20Voice|Auto')));
assert(modernLabels.some((label) => label.startsWith('@VIBEEPISODE@1|1|0|0|First|1080p|Dub%20Voice')));
assert(modernLabels.some((label) => label.startsWith('@VIBEEPISODE@1|1|0|0|First|1080p|Original')));

console.log('plugin bridge tests passed');
