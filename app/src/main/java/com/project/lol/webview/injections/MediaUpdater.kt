package com.project.lol.webview.injections

object MediaUpdater {
    const val CONTENT = """
            window.updMedia = function(){
                var album=window.__curTrackAlbum||'';
                var currState=track+'|'+artist+'|'+playing+'|'+repmode+'|'+isfav+'|'+shuffle+'|'+album;
                if(currState!==lastState) {
                    lastState=currState;
                    var values={artist:artist,track:track,album:album,playing:playing,repeat:repmode,fav:isfav,shuffle:shuffle,duration:duration,position:position,cover:cover};
                    AndBridge.recMediaStatus(JSON.stringify(values));
                } else {
                    AndBridge.recMediaPosition(position);
                    lastPos=position;
                }
            };
        
    """
}
