// Scrolls the preview to the element generated from a given Markdown source line (data-line attributes),
// interpolating between elements, so that the line appears at the same height as in the editor.
(function () {
    function top(el) {
        return el.getBoundingClientRect().top + window.pageYOffset;
    }

    // footnote definitions are rendered at the end of the page, away from their source position
    function inFootnotes(el) {
        for (var e = el; e !== null && e !== document.body; e = e.parentElement) {
            if (e.classList && e.classList.contains('footnotes')) {
                return true;
            }
        }
        return false;
    }

    // edge: -1 = editor at its top, 1 = editor at its bottom, 0 = anywhere else
    window.mdxSyncToLine = function (line, fraction, edge) {
        if (edge === -1) {
            window.scrollTo(0, 0);
            return;
        }
        if (edge === 1) {
            window.scrollTo(0, document.documentElement.scrollHeight);
            return;
        }
        var els = document.querySelectorAll('[data-line]');
        var before = null, beforeLine = -1, after = null, afterLine = 0;
        for (var i = 0; i < els.length; i++) {
            var el = els[i];
            if (el.offsetParent === null || inFootnotes(el)) {
                continue; // hidden, or not in source order
            }
            var l = parseInt(el.getAttribute('data-line'), 10);
            if (l <= line) {
                if (l >= beforeLine) {
                    before = el;
                    beforeLine = l;
                }
            } else if (after === null || l < afterLine) {
                after = el;
                afterLine = l;
            }
        }
        var y = 0;
        if (before !== null) {
            var y0 = top(before);
            if (after !== null && afterLine > beforeLine) {
                var y1 = top(after);
                y = y0 + Math.max(0, y1 - y0) * (line - beforeLine) / (afterLine - beforeLine);
            } else {
                // last element: move through it proportionally to the remaining lines
                var height = before.getBoundingClientRect().height;
                y = y0 + Math.min(height, height * Math.min(1, (line - beforeLine) / 20));
            }
        } else if (after !== null) {
            y = 0;
        }
        window.scrollTo(0, Math.max(0, y - fraction * window.innerHeight));
    };
})();
