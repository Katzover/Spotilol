package com.project.lol.webview.injections

object PlaybackControls {
    const val CONTENT = """
            window.playFromUri = function(uri, contextUri) {
                var playContext = contextUri || uri;
                var isLikedSongs = (playContext === 'your_library' || playContext.indexOf('collection') !== -1 || playContext === 'playlists' || playContext === 'spotify:collection:tracks');

                var playOptions = {
                    license: 'tft',
                    skip_to: {},
                    player_options_override: {}
                };

                var commandContext = {
                    uri: playContext,
                    url: 'context://' + playContext,
                    metadata: {}
                };

                var featIdent = playContext.match(/^spotify:([^:]+)/);
                featIdent = featIdent ? featIdent[1] : null;
                if (featIdent == 'user' || isLikedSongs) featIdent = 'your_library';

                if (isLikedSongs) {
                    var tracks = window.likedSongsCache || [];
                    var targetUri = (uri && uri.indexOf(':track:') !== -1) ? uri : ((tracks[0] && tracks[0].id) || uri);
                    var trackList = (tracks || []).map(function(t) { return t.id || t.uri || t; }).filter(Boolean);

                    if (targetUri && targetUri.indexOf(':track:') !== -1 && trackList.indexOf(targetUri) === -1) {
                        trackList.unshift(targetUri);
                    }

                    var collectionUri = window.spotUserId ? 'spotify:user:' + window.spotUserId + ':collection' : 'spotify:collection:tracks';

                    commandContext = {
                        uri: collectionUri,
                        url: 'context://' + collectionUri,
                        metadata: { context_description: 'Liked Songs' },
                        pages: [{ page_url: 'context://' + collectionUri, tracks: trackList.map(function(u) { return { uri: u }; }) }]
                    };

                    featIdent = 'collection-tracks';

                    playOptions.skip_to = { track_uri: targetUri };
                } else if (contextUri && contextUri !== uri) {
                    playOptions.skip_to = { track_uri: uri };
                }

                (window.mngFetch || oriFetch)('https://gew4-spclient.spotify.com/connect-state/v1/player/command/from/' + window.spotDevId + '/to/' + window.spotDevId, {
                    method: 'POST',
                    headers: { 'Authorization': window.spotAuthToken, 'Client-Token': window.spotCliToken, 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        command: {
                            context: commandContext,
                            play_origin: {
                                feature_identifier: featIdent || 'your_library',
                                feature_version: featVer,
                                referrer_identifier: 'your_library'
                            },
                            options: playOptions,
                            endpoint: 'play'
                        }
                    })
                });
            };
            window.splEnsurePb=function(){
                var pb=window.pBtn;
                if(pb && document.documentElement.contains(pb)) return pb;
                var all=document.querySelectorAll('aside button[data-testid=control-button-playpause], button[data-testid=control-button-playpause]');
                var first=null;
                for(var i=0;i<all.length;i++){
                    if(first===null) first=all[i];
                    if(all[i].getClientRects().length>0){ first=all[i]; break; }
                }
                if(first){ window.pBtn=first; return first; }
                try{ AndBridge.dbg('e','bt auto-pause: no play button found'); }catch(e){}
                return null;
            };
            window.splPauseOutcome=function(cb){
                var s1=window.splIsPlaying();
                if(s1===false){ cb('paused'); return; }
                setTimeout(function(){
                    var s2=window.splIsPlaying();
                    if(s2===false) cb('paused');
                    else if(s1===true && s2===true) cb('playing');
                    else cb('unknown');
                },400);
            };
            window.splPauseRetry=function(tries){
                tries=tries||0;
                var cur=(typeof window.splIsPlaying==='function')?window.splIsPlaying():null;
                if(cur===false) return;
                if(tries>0 && cur!==true) return;
                var pb=window.splEnsurePb();
                if(!pb) return;
                try{ window.reqPause=true; window.ulFlag=false; }catch(e){}
                pb.click();
                setTimeout(function(){
                    window.splPauseOutcome(function(outcome){
                        if(outcome==='paused'){
                            try{ AndBridge.dbg('s','bt auto-pause: confirmed paused'); }catch(e){}
                            return;
                        }
                        if(outcome==='unknown'){
                            try{ AndBridge.dbg('w','bt auto-pause: play state unknown, not retrying'); }catch(e){}
                            return;
                        }
                        if(tries>=3){
                            try{ AndBridge.dbg('e','bt auto-pause: playback continued after '+(tries+1)+' attempts'); }catch(e){}
                            return;
                        }
                        try{ AndBridge.dbg('w','bt auto-pause: still playing, retry '+(tries+1)); }catch(e){}
                        window.splPauseRetry(tries+1);
                    });
                },900);
            };
            window.actPlayPause = function(play) {
                var pb = window.splEnsurePb();
                if (!pb) return;
                var cur = (typeof window.splIsPlaying === 'function') ? window.splIsPlaying() : null;
                if (play === null || typeof play === 'undefined' || cur === null) {
                    pb.click();
                } else if (play === true) {
                    if (!cur) pb.click();
                } else if (play === false) {
                    window.splPauseRetry(0);
                }
            };
            window.actSkipBack = function() {
                var bb = document.querySelector('button[data-testid=control-button-skip-back]');
                if(bb) { AndBridge.wakeUp(); bb.click(); }
            };
            window.actSkipForward = function() {
                var fb = document.querySelector('button[data-testid=control-button-skip-forward]');
                if(fb) { AndBridge.wakeUp(); fb.click(); }
            };
            window.actToggleShuffle = function() {
                var sb = document.querySelector('button[data-testid="control-button-shuffle"]');
                if(!sb) {
                    var allb=document.querySelectorAll('button');
                    for(var i=0;i<allb.length;i++){
                        var sbal=allb[i].getAttribute('aria-label')||'';
                        if(/shuffle/i.test(sbal)&&!/spl-btn/.test(allb[i].className||'')){ sb=allb[i]; break; }
                    }
                }
                if(sb && sb.getAttribute('aria-disabled')!=='true') {
                    AndBridge.wakeUp();
                    sb.click();
                }
            };
            window.actRepeat = function() {
                var rb = document.querySelector('button[data-testid=control-button-repeat]');
                if(rb) {
                    if(repmode=='false') repmode='true';
                    else if(repmode=='true') repmode='mixed';
                    else repmode='false';
                    updMedia();
                    rb.click();
                }
            };
            window.actAddToFav = function() {
                var fb = document.querySelector('div[data-testid=now-playing-widget]>div:last-child>button');
                if(fb) {
                    if(fb.getAttribute('aria-checked')==='false') {
                        fb.click();
                        isfav=true;
                        updMedia();
                    } else {
                        AndBridge.wakeUp();
                        fb.click();
                        var rfint = setInterval(function(){
                            var fr = document.querySelector('#context-menu button[role=menuitemcheckbox][aria-checked=true]');
                            if(fr) {
                                clearInterval(rfint);
                                fr.click();
                                setTimeout(function(){
                                    var sb = document.querySelector('#context-menu button[type=submit]');
                                    if(sb) { sb.click(); isfav=false; updMedia(); }
                                    AndBridge.wakeOff();
                                },500);
                            }
                        },1000);
                    }
                }
            };
            window.actSeek = function(pos) {
                var rg = document.querySelector('div[data-testid=playback-progressbar] input[type=range]');
                if(rg) { rg.value=pos+1; rg.dispatchEvent(new Event('change',{bubbles:true})); }
            };
        
    """
}
