'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const pluginSource = fs.readFileSync(__dirname + '/VibePlayer-Lampa-Plugin.js', 'utf8');
const logs = [];
let forwardedPayload;

const context = {
    console: {
        info: (message) => logs.push(String(message)),
        warn: (message) => logs.push(String(message))
    },
    window: {
        location: { origin: 'http://lampa.mx' },
        navigator: { userAgent: 'Lampa WebView Test' }
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
    headers: {
        Cookie: 'source-provided-cookie',
        'X-Source-Header': 'source-provided-value'
    },
    quality: {
        '1080p': 'https://media.example/current-1080.m3u8'
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
assert.equal(forwarded.headers.Cookie, 'source-provided-cookie');
assert.equal(forwarded.headers['X-Source-Header'], 'source-provided-value');
assert.equal(forwarded.headers.Referer, undefined);
assert.equal(forwarded.headers.Origin, undefined);
assert.equal(forwarded.headers['User-Agent'], undefined);
assert.equal(context.window.VibePlayerBridge.version, '0.5.0');
assert.equal(context.window.VibePlayerBridge.lastStats.captured, true);
assert.deepEqual(Array.from(context.window.VibePlayerBridge.lastCapture.headerNames), ['Cookie', 'X-Source-Header']);
assert(!logs.join('\n').includes('media.example'));
assert(!/\bfetch\s*\(/.test(pluginSource));
assert(!/XMLHttpRequest|Lampa\.Reguest|Lampa\.Request/.test(pluginSource));

console.log('plugin bridge tests passed');
