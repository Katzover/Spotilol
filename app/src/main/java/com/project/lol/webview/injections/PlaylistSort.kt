package com.project.lol.webview.injections

/**
 * sort the rows of a playlist from the columns dropdown.
 *
 * spotify already ships a full sort engine but never wires it up for playlist
 * tracklists, so the headers are dead divs and the api ignores the sort variable
 * too. what does work is sorting the items as they come back from pathfinder,
 * then poking the query cache so the page refetches and the new order actually
 * lands on screen.
 *
 * the request is rewritten to pull the whole playlist in one go so the order is
 * global and not per page. the sorted list is kept in memory so the following
 * pages cost no extra round trip. huge playlists just get sorted per page.
 *
 * the sort lives in a sort by section added to the columns dropdown, which is
 * where people already look for column stuff. the rows are clones of spotify own
 * column rows, but the tick is swapped for a direction arrow, since a tick down
 * there just reads as one more column toggle and the section goes unnoticed.
 * tapping a row goes a to z, then z to a, then back to the original order, and
 * the arrow in the menu plus the one on the header show the direction at a glance.
 */
object PlaylistSort {
    const val CONTENT = """
        (function(){
            if (window.splPlaylistSort) return;

            var LS_KEY = 'spotilol_playlist_sort';
            var RELOAD_KEY = 'spotilol_sort_reload';
            var MAX_LIMIT = 1000;
            var TTL = 30000;
            var STYLE_ID = 'spotilol-sort-style';

            var state = null;
            try {
                var raw = localStorage.getItem(LS_KEY);
                if (raw) state = JSON.parse(raw);
            } catch(e){ state = null; }
            if (state && (!state.field || (state.order !== 'ASC' && state.order !== 'DESC'))) state = null;

            var held = {};
            var sortedHits = {};
            var qcCache = null;
            var menuTimer = null;
            var menuObserver = null;

            // label shown in the dropdown, the column name comes from spotify own rows
            var SORT_FIELDS = [
                ['TITLE_AND_ARTIST', '', 'Title'],
                ['ALBUM', 'ALBUM', 'Album'],
                ['ADDED_AT', 'ADDED_AT', 'Date added'],
                ['RELEASE_DATE', 'RELEASE_DATE', 'Release date'],
                ['DURATION', 'DURATION', 'Duration']
            ];

            function enabled(){ return window.__splPlaylistSortEnabled !== false; }
            function sortKey(){ return state ? state.field + ':' + state.order : ''; }
            function currentUri(){
                var m = location.pathname.match(/^\/playlist\/([A-Za-z0-9]+)/);
                if (m) return 'spotify:playlist:' + m[1];
                m = location.pathname.match(/^\/album\/([A-Za-z0-9]+)/);
                if (m) return 'spotify:album:' + m[1];
                return null;
            }
            function albumPage(){ return /^\/album\//.test(location.pathname); }
            function save(){
                try {
                    if (state) localStorage.setItem(LS_KEY, JSON.stringify(state));
                    else localStorage.removeItem(LS_KEY);
                } catch(e){}
            }

            // what we sort by, straight from the pathfinder items
            function valueOf(item, field){
                if (albumPage()) {
                    var t = item && item.track;
                    if (!t) return null;
                    switch (field) {
                        case 'TITLE_AND_ARTIST': return t.name || null;
                        case 'DURATION': return (t.duration && t.duration.totalMilliseconds) || 0;
                    }
                    return null;
                }
                var d = item && item.itemV2 ? item.itemV2.data : null;
                if (!d) return null;
                switch (field) {
                    case 'TITLE_AND_ARTIST': return d.name || null;
                    case 'ALBUM': return (d.albumOfTrack && d.albumOfTrack.name) || null;
                    case 'DURATION': return (d.trackDuration && d.trackDuration.totalMilliseconds) || 0;
                    case 'RELEASE_DATE': return (d.albumOfTrack && d.albumOfTrack.date && d.albumOfTrack.date.isoString) || null;
                    case 'ADDED_AT': return (item.addedAt && item.addedAt.isoString) || null;
                }
                return null;
            }

            function sortItems(items){
                if (!state) return items;
                var field = state.field;
                var dir = state.order === 'DESC' ? -1 : 1;
                return items.slice().sort(function(a, b){
                    var x = valueOf(a, field), y = valueOf(b, field);
                    // rows with no value for this column go last
                    if (x === null && y === null) return 0;
                    if (x === null) return 1;
                    if (y === null) return -1;
                    if (typeof x === 'string' || typeof y === 'string') {
                        return String(x).localeCompare(String(y)) * dir;
                    }
                    if (x === y) return 0;
                    return (x < y ? -1 : 1) * dir;
                });
            }
            // helpers to read and rebuild the response
            function readItems(j){
                try { return j.data.playlistV2.content.items || null; } catch(e){}
                try { return j.data.albumUnion.tracksV2.items || null; } catch(e){}
                return null;
            }
            function readTotal(j, fallback){
                try {
                    var t = j.data.playlistV2.content.totalCount;
                    if (typeof t === 'number') return t;
                } catch(e){}
                try {
                    var a = j.data.albumUnion.tracksV2.totalCount;
                    if (typeof a === 'number') return a;
                } catch(e){}
                return fallback;
            }
            function rebuild(j, items){
                var out = j;
                try {
                    if (j.data.playlistV2) {
                        var content = Object.assign({}, j.data.playlistV2.content, { items: items });
                        var pv2 = Object.assign({}, j.data.playlistV2, { content: content });
                        out = Object.assign({}, j, { data: Object.assign({}, j.data, { playlistV2: pv2 }) });
                    } else if (j.data.albumUnion) {
                        var tv2 = Object.assign({}, j.data.albumUnion.tracksV2, { items: items });
                        var au = Object.assign({}, j.data.albumUnion, { tracksV2: tv2 });
                        out = Object.assign({}, j, { data: Object.assign({}, j.data, { albumUnion: au }) });
                    }
                } catch(e){}
                var headers = null;
                try {
                    headers = new Headers();
                    headers.set('content-type', 'application/json');
                } catch(e){ headers = null; }
                return new Response(JSON.stringify(out), { status: 200, statusText: 'OK', headers: headers });
            }

            // react query access, only used to trigger a refetch
            function findQueryClient(){
                if (qcCache) return qcCache;
                var el = document.querySelector('[data-testid="playlist-tracklist"]') || document.body;
                if (!el) return null;
                var keys = Object.keys(el), fkey = null;
                for (var i = 0; i < keys.length; i++) {
                    if (keys[i].indexOf('__reactFiber${'$'}') === 0) { fkey = keys[i]; break; }
                }
                if (!fkey) return null;
                var fiber = el[fkey];
                while (fiber && fiber.return) fiber = fiber.return;
                if (!fiber) return null;
                var stack = [fiber], seen = [], guard = 0;
                while (stack.length && guard++ < 60000) {
                    var node = stack.pop();
                    if (!node || seen.indexOf(node) !== -1) continue;
                    seen.push(node);
                    var props = node.memoizedProps;
                    if (props && props.value
                        && typeof props.value.getQueryData === 'function'
                        && typeof props.value.invalidateQueries === 'function') {
                        qcCache = props.value;
                        return qcCache;
                    }
                    if (node.child) stack.push(node.child);
                    if (node.sibling) stack.push(node.sibling);
                }
                return null;
            }

            // refetch the current playlist so the sort actually lands. a playlist
            // that is already cached is never fetched again by browsing around, so
            // we poke its queries ourselves.
            function apply(){
                var uri = currentUri();
                if (!uri) return false;
                // an album resolves its rows by index and drops back to album order on any
                // update, so refetching here would undo the sort. its order comes with the
                // payload at mount instead
                if (albumPage()) return false;
                // a playlist we already fed sorted rows has to be refetched even when the
                // sort just got turned off, otherwise the list keeps the order from the
                // cache and it looks sorted while the menu says the sort is off
                var wasSorted = !!held[uri] || !!state;
                held[uri] = null;
                if (!enabled() || !wasSorted) return false;
                if (state && !fieldSupported(state.field)) return false;
                var qc = findQueryClient();
                if (!qc || typeof qc.getQueryCache !== 'function') return false;
                var all = [];
                try { all = qc.getQueryCache().getAll() || []; } catch(e){ return false; }
                var hit = false;
                for (var i = 0; i < all.length; i++) {
                    var q = all[i];
                    if (!q || !q.queryKey) continue;
                    var str;
                    try { str = JSON.stringify(q.queryKey); } catch(e){ continue; }
                    if (str.indexOf(uri) === -1) continue;
                    try { qc.invalidateQueries({ queryKey: q.queryKey }); hit = true; } catch(e){}
                }
                return hit;
            }
            // column headers, which ones sort and the little arrow
            function injectStyle(){
                if (document.getElementById(STYLE_ID)) return;
                var s = document.createElement('style');
                s.id = STYLE_ID;
                s.textContent =
                    '[role="columnheader"][data-spl-sort]{-webkit-user-select:none;user-select:none}' +
                    '[role="columnheader"][data-spl-sort="ASC"]::after{content:"\\25B2";font-size:8px;margin-left:4px;opacity:.9}' +
                    '[role="columnheader"][data-spl-sort="DESC"]::after{content:"\\25BC";font-size:8px;margin-left:4px;opacity:.9}' +
                    // the sort section has to read as its own group, spotify gives the
                    // heading the same look as any other section so we add the rule
                    '[data-spl-opt="head"]{border-top:1px solid rgba(255,255,255,.12)}' +
                    '.spl-sort-arrow{display:flex;align-items:center;justify-content:center;width:16px;height:16px;font-size:9px;line-height:1;color:var(--text-bright-accent,#1ed760)}';
                (document.head || document.documentElement).appendChild(s);
            }

            function fieldFor(label){
                var t = String(label || '').replace(/\s+/g, ' ').trim().toLowerCase();
                if (!t) return null;
                if (t.indexOf('title') === 0) return 'TITLE_AND_ARTIST';
                if (t === 'album') return 'ALBUM';
                if (t.indexOf('duration') === 0) return 'DURATION';
                if (t.indexOf('release') === 0) return 'RELEASE_DATE';
                if (t.indexOf('date added') === 0) return 'ADDED_AT';
                return null;
            }

            function labelOf(cell){
                var label = cell.textContent || '';
                if (!label.trim()) {
                    var icon = cell.querySelector('[aria-label]');
                    if (icon) label = icon.getAttribute('aria-label') || '';
                }
                return label;
            }

            function decorate(){
                try {
                    injectStyle();
                    var cells = document.querySelectorAll('[role="columnheader"]');
                    var uri = currentUri();
                    for (var i = 0; i < cells.length; i++) {
                        var cell = cells[i];
                        var field = uri ? fieldFor(labelOf(cell)) : null;
                        if (!enabled() || !field) {
                            if (cell.hasAttribute('data-spl-sort')) cell.removeAttribute('data-spl-sort');
                            continue;
                        }
                        var active = (state && state.field === field) ? state.order : '';
                        if (active) {
                            cell.setAttribute('data-spl-sort', active);
                            cell.setAttribute('aria-sort', active === 'ASC' ? 'ascending' : 'descending');
                        } else {
                            if (cell.hasAttribute('data-spl-sort')) cell.removeAttribute('data-spl-sort');
                            cell.setAttribute('aria-sort', 'none');
                        }
                    }
                    injectMenu();
                } catch(e){}
            }

            // the columns dropdown, only ours to touch when it really is the columns
            // one. keyed off data column so a locale change cannot break the match
            function columnsMenu(){
                var menu = document.getElementById('context-menu');
                if (!menu || !menu.children || !menu.children.length) return null;
                if (!menu.querySelector('button[role="menuitemcheckbox"][data-column]')) return null;
                return menu.querySelector('ul[role="menu"]');
            }
            // the section heading in the menu has no button inside it
            function sectionTemplate(ul){
                for (var i = 0; i < ul.children.length; i++) {
                    var li = ul.children[i];
                    if (li.tagName === 'LI' && !li.querySelector('button')) return li.cloneNode(true);
                }
                return null;
            }
            function rowTemplate(ul){
                var b = ul.querySelector('button[role="menuitemcheckbox"][data-column]');
                return (b && b.closest('li')) ? b.closest('li').cloneNode(true) : null;
            }
            // the tick spotify draws is what makes a row read as a column toggle, so
            // sort rows swap it for a direction arrow. the slot is kept so rows align
            function arrowFor(li, active){
                var mark = li.querySelector('.spl-sort-arrow');
                if (!mark) return;
                mark.textContent = active ? (active === 'DESC' ? '\u25BC' : '\u25B2') : '';
            }
            function setActive(li, active){
                var btn = li.querySelector('button');
                if (!btn) return;
                btn.setAttribute('aria-checked', active ? 'true' : 'false');
                var sp = btn.querySelector('[data-encore-id="text"]');
                if (sp) {
                    if (active) sp.className = sp.className.replace('encore-internal-color-text-base', 'encore-internal-color-text-bright-accent');
                    else sp.className = sp.className.replace('encore-internal-color-text-bright-accent', 'encore-internal-color-text-base');
                }
                arrowFor(li, active);
            }
            // reuse spotify own wording so the row matches the rest of the menu
            function columnLabel(ul, col, fallback){
                if (!col) return fallback;
                var b = ul.querySelector('button[role="menuitemcheckbox"][data-column="' + col + '"]');
                var sp = b ? b.querySelector('span') : null;
                var t = sp ? String(sp.textContent || '').replace(/\s+/g, ' ').trim() : '';
                return t || fallback;
            }
            function headerLabel(field){
                var cells = document.querySelectorAll('[role="columnheader"]');
                for (var i = 0; i < cells.length; i++) {
                    if (fieldFor(labelOf(cells[i])) !== field) continue;
                    var t = String(labelOf(cells[i]) || '').replace(/\s+/g, ' ').trim();
                    if (t) return t;
                }
                return null;
            }
            // date added is only real on the playlists that carry it. editorial ones
            // ship a dummy 1970 stamp and hide the column, so the row would sort nothing
            function fieldSupported(field){
                // an album has no date added, and album/release date are the same for every row
                if (albumPage()) return field === 'TITLE_AND_ARTIST' || field === 'DURATION';
                if (field !== 'ADDED_AT') return true;
                var ul = columnsMenu();
                if (ul && ul.querySelector('button[role="menuitemcheckbox"][data-column="' + field + '"]')) return true;
                return !!headerLabel(field);
            }
            function dropMenuRows(ul){
                var old = ul.querySelectorAll('[data-spl-opt]');
                for (var i = 0; i < old.length; i++) {
                    var li = old[i].closest ? old[i].closest('li') : null;
                    if (li && li.parentNode) li.parentNode.removeChild(li);
                    else if (old[i].parentNode) old[i].parentNode.removeChild(old[i]);
                }
            }

            function injectMenu(){
                try {
                    if (!enabled() || !currentUri()) return;
                    var ul = columnsMenu();
                    if (!ul) return;
                    var stamp = sortKey();
                    if (ul.querySelectorAll('[data-spl-opt]').length && ul.getAttribute('data-spl-menu') === stamp) return;
                    dropMenuRows(ul);
                    var row = rowTemplate(ul);
                    if (!row) return;
                    var head = sectionTemplate(ul);
                    if (head) {
                        var hs = head.querySelector('span');
                        if (hs) hs.textContent = 'Sort by';
                        head.setAttribute('data-spl-opt', 'head');
                        ul.appendChild(head);
                    }
                    for (var i = 0; i < SORT_FIELDS.length; i++) {
                        var field = SORT_FIELDS[i][0];
                        if (!fieldSupported(field)) continue;
                        var label = columnLabel(ul, SORT_FIELDS[i][1], headerLabel(field) || SORT_FIELDS[i][2]);
                        var active = (state && state.field === field) ? state.order : '';
                        var li = row.cloneNode(true);
                        var btn = li.querySelector('button');
                        if (!btn) continue;
                        btn.setAttribute('data-spl-opt', 'field');
                        btn.setAttribute('data-spl-field', field);
                        btn.setAttribute('role', 'menuitemradio');
                        btn.setAttribute('aria-label', label);
                        btn.removeAttribute('data-column');
                        // the native tick would read as one more column toggle, so it
                        // gets swapped for a direction arrow in the same slot
                        var cb = btn.querySelector('[data-encore-id="formCheckbox"]');
                        if (cb && cb.parentNode) {
                            var mark = document.createElement('span');
                            mark.className = 'spl-sort-arrow';
                            mark.setAttribute('aria-hidden', 'true');
                            cb.parentNode.replaceChild(mark, cb);
                        }
                        var sp = btn.querySelector('[data-encore-id="text"]');
                        if (sp) sp.textContent = label;
                        if (active) btn.setAttribute('aria-label', label + (active === 'DESC' ? ', z to a' : ', a to z'));
                        setActive(li, active);
                        ul.appendChild(li);
                    }
                    ul.setAttribute('data-spl-menu', stamp);
                } catch(e){}
            }
            // the menu is built by react on every open, so watch for it to show up.
            // the check is one getElementById so a busy page costs nothing
            function scheduleMenu(){
                if (menuTimer) return;
                menuTimer = setTimeout(function(){ menuTimer = null; injectMenu(); }, 80);
            }
            function observeMenu(){
                try {
                    if (menuObserver || !window.MutationObserver) return;
                    var root = document.body || document.documentElement;
                    if (!root) return;
                    menuObserver = new MutationObserver(function(){
                        if (document.getElementById('context-menu')) scheduleMenu();
                    });
                    menuObserver.observe(root, { childList: true, subtree: true });
                } catch(e){}
            }

            function albumReload(){
                var key = sortKey() || 'off';
                var n = 0;
                try { n = parseInt(sessionStorage.getItem(RELOAD_KEY + ':' + key) || '0', 10) || 0; } catch(e){}
                if (n >= 2) return;
                try { sessionStorage.setItem(RELOAD_KEY + ':' + key, String(n + 1)); } catch(e){}
                setTimeout(function(){ try { location.reload(); } catch(e){} }, 200);
            }
            function afterSortChange(){
                decorate();
                if (!albumPage()) { apply(); return; }
                // an album reads its rows once at mount, so a new order only lands on a fresh
                // load. the counter caps it per chosen sort so it cannot reload in a loop
                albumReload();
            }
            // public api
            function setSort(field, order){
                if (!field) { clearSort(); return; }
                state = { field: field, order: order === 'DESC' ? 'DESC' : 'ASC' };
                save();
                afterSortChange();
            }
            function clearSort(){
                state = null;
                save();
                afterSortChange();
            }
            function toggleField(field){
                if (!field) return;
                if (state && state.field === field && state.order === 'ASC') { setSort(field, 'DESC'); return; }
                if (state && state.field === field && state.order === 'DESC') { clearSort(); return; }
                setSort(field, 'ASC');
            }
            function refresh(){
                afterSortChange();
            }

            window.splPlaylistSort = {
                get: function(){ return state ? { field: state.field, order: state.order } : null; },
                set: setSort,
                clear: clearSort,
                cycle: toggleField,
                refresh: refresh
            };

            // fetch interceptor
            var prevFetch = window.fetch.bind(window);
            window.fetch = function(input, init){
                try {
                    var url = typeof input === 'string' ? input : (input && input.url) || '';
                    if (!enabled() || !state || !fieldSupported(state.field)
                        || url.indexOf('api-partner.spotify.com/pathfinder') === -1
                        || !init || !init.body) {
                        return prevFetch(input, init);
                    }
                    var body = init.body;
                    var parsed = typeof body === 'string' ? JSON.parse(body) : body;
                    var op = parsed && parsed.operationName;
                    var playlistOp = (op === 'fetchPlaylistContents' || op === 'fetchPlaylist');
                    if (!parsed || (op !== 'getAlbum' && !playlistOp)) {
                        return prevFetch(input, init);
                    }
                    var vars = parsed.variables || {};
                    var uri = vars.uri;
                    var want = playlistOp ? 'spotify:playlist:' : 'spotify:album:';
                    if (!uri || String(uri).indexOf(want) !== 0) return prevFetch(input, init);

                    var offset = vars.offset | 0;
                    var limit = (vars.limit | 0) || 50;
                    var key = sortKey();
                    var hit = held[uri];
                    if (!hit || hit.key !== key || (Date.now() - hit.ts) >= TTL) hit = null;

                    // a page we already sorted, serve it from memory with no round trip.
                    // holding the whole playlist means a page past its end is just empty
                    if (hit && (offset < hit.items.length || hit.total <= hit.items.length)) {
                        return Promise.resolve(rebuild(hit.envelope, hit.items.slice(offset, offset + limit)));
                    }

                    if (!hit) {
                        var req = Object.assign({}, parsed);
                        req.variables = Object.assign({}, vars, { offset: 0, limit: MAX_LIMIT });
                        var init2 = Object.assign({}, init);
                        init2.body = typeof body === 'string' ? JSON.stringify(req) : req;
                        return prevFetch(input, init2).then(function(resp){
                            return resp.clone().json().then(function(j){
                                var items = readItems(j);
                                if (!items || !items.length) return resp;
                                var sorted = sortItems(items);
                                held[uri] = {
                                    items: sorted,
                                    envelope: j,
                                    total: readTotal(j, sorted.length),
                                    key: key,
                                    ts: Date.now()
                                };
                                if (albumPage()) sortedHits[uri + '|' + key] = 1;
                                return rebuild(j, sorted.slice(offset, offset + limit));
                            }).catch(function(){ return resp; });
                        });
                    }

                    // a range past the list we hold, so the playlist is bigger than our
                    // max limit. fetch that page and sort it on its own
                    return prevFetch(input, init).then(function(resp){
                        return resp.clone().json().then(function(j){
                            var items = readItems(j);
                            if (!items || !items.length) return resp;
                            return rebuild(j, sortItems(items));
                        }).catch(function(){ return resp; });
                    });
                } catch(e) {
                    return prevFetch(input, init);
                }
            };

            // picking a row from the sort by section.
            // the menu is left open on purpose so the tick and the direction update
            // in front of you, then a tap outside closes it like any other menu
            document.addEventListener('click', function(ev){
                try {
                    var target = ev.target;
                    if (!target || !target.closest) return;
                    var opt = target.closest('[data-spl-opt="field"]');
                    if (!opt) return;
                    ev.preventDefault();
                    ev.stopPropagation();
                    toggleField(opt.getAttribute('data-spl-field'));
                } catch(e){}
            }, true);

            observeMenu();
            injectStyle();
            decorate();
            setInterval(decorate, 1000);
            // apply once the player has mounted so a saved choice survives reloads and
            // also covers playlists served straight from the query cache
            if (state) {
                if (albumPage()) {
                    // a load where the app asked for the album before this script was in place
                    // leaves the rows in album order with no refetch to fix it, so heal once
                    setTimeout(function(){
                        if (sortedHits[currentUri() + '|' + sortKey()]) return;
                        albumReload();
                    }, 2500);
                } else {
                    setTimeout(apply, 1500);
                }
            }
        })();
    """
}
