'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const pluginSource = fs.readFileSync(__dirname + '/VibePlayer-Lampa-Plugin.js', 'utf8');
const loaderSource = fs.readFileSync(__dirname + '/v.js', 'utf8');
const logs = [];
let forwardedPayload;

const context = {
    console: {
        info: (message) => logs.push(String(message)),
        warn: (message) => logs.push(String(message))
    },
    window: {
        location: { origin: 'http://lampa.mx' },
        navigator: { userAgent: 'Lampa WebView Test', language: 'uk-UA' }
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
        '720p': 'https://media.example/current-720.m3u8'
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
    playlist: [{
        season: 1,
        episode: 2,
        title: 'Second Episode',
        timeline: { percent: 94, time: 122 },
        quality: {
            '1080p': 'https://media.example/s01e02-1080.m3u8'
        }
    }]
};

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
assert(labels.some((label) => label.startsWith('@VIBEEPISODE@1|2|94|122|Second%20Episode|1080p')));

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
assert.equal(context.window.VibePlayerBridge.version, '0.14.0');
assert.equal(context.window.VibePlayerBridge.lastStats.captured, true);
assert.equal(context.window.VibePlayerBridge.lastStats.headers, 8);
assert.deepEqual(Array.from(context.window.VibePlayerBridge.lastCapture.headerNames), ['Cookie', 'X-Source-Header']);
assert(!logs.join('\n').includes('media.example'));
assert(!/\bfetch\s*\(/.test(pluginSource));
assert(!/XMLHttpRequest|Lampa\.Reguest|Lampa\.Request/.test(pluginSource));
assert(loaderSource.includes('VibePlayer-Lampa-Plugin.js?v=0.14.0'));

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
assert.deepEqual(Object.keys(unrelated.quality), ['@VIBEMETA@||c0p1v1f9']);

// The probe reports the capture structurally: matched, 1 playlist entry, 1 voiceover,
// 9 top-level fields. It must never carry anything resembling a URL.
const probe = Object.keys(forwarded.quality)
    .find((label) => label.startsWith('@VIBEMETA@'))
    .split('|')[2];
assert.match(probe, /^c1p1v1f9$/);

// The bridge must report itself installed, otherwise it is silently doing nothing.
assert.equal(context.window.VibePlayerBridge.installed, true);

console.log('plugin bridge tests passed');
