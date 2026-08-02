(function () {
    // Lampa: short public alias for the full VibePlayer bridge.
    if (window.VibePlayerBridge && window.VibePlayerBridge.version === '0.5.0') return;
    var script = document.createElement('script');
    script.src = 'https://funk4d.github.io/VibePlayer/VibePlayer-Lampa-Plugin.js';
    document.head.appendChild(script);
})();
