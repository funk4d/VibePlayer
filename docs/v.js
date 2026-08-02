(function () {
    // Lampa: short public alias for the full VibePlayer bridge.
    if (window.VibePlayerBridge && window.VibePlayerBridge.version === '0.9.0') return;
    var script = document.createElement('script');
    script.src = 'https://funk4d.github.io/VibePlayer/VibePlayer-Lampa-Plugin.js?v=0.9.0';
    document.head.appendChild(script);
})();
