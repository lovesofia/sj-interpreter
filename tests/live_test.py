"""E2E: 실시간 음성 통역 흐름 (가짜 마이크 + 가짜 Gemini Live 서버)"""
import json,sys
from playwright.sync_api import sync_playwright
OUT=sys.argv[1]
FAKE_WS = r"""
(() => {
  const PCM = btoa(String.fromCharCode(...new Uint8Array(4800)));   // 0.1s of silence @24k
  window.__ws = []; 
  class FakeWS {
    constructor(url){ this.url=url; this.readyState=0; this.sent=[]; window.__ws.push(this);
      setTimeout(()=>{ this.readyState=1; this.onopen && this.onopen(); },30); }
    send(d){ const m=JSON.parse(d); this.sent.push(m);
      if(m.setup){ if('inputAudioTranscription' in m.setup.generationConfig || !('inputAudioTranscription' in m.setup)){ this.onclose&&this.onclose({code:1007,reason:'Invalid JSON payload received. Unknown name "inputAudioTranscription"'}); return; } this.target=m.setup.generationConfig.translationConfig.targetLanguageCode; this.setup=m.setup;
        setTimeout(()=>this._emit({setupComplete:{}}),30); return; }
      if(m.realtimeInput){ this.audioCount=(this.audioCount||0)+1;
        // 3번째 오디오 덩어리를 받으면: 대표가 한국어로 말한 상황을 흉내
        if(this.audioCount===3){
          this._emit({serverContent:{inputTranscription:{text:"3번 분만틀 모돈 ",languageCode:"ko"}}});
          this._emit({serverContent:{inputTranscription:{text:"확인해",languageCode:"ko"}}});
          if(this.target!=="ko"){
            this._emit({serverContent:{outputTranscription:{text:"३ नम्बर क्रेटको माउ सुँगुर जाँच गर्नुहोस्",languageCode:this.target},modelTurn:{parts:[{inlineData:{mimeType:"audio/pcm;rate=24000",data:PCM}}]}}});
          }
          this._emit({serverContent:{turnComplete:true}});
        }
        if(this.audioCount===12){
          this._emit({serverContent:{inputTranscription:{text:"दुईवटा बच्चा कमजोर छन्",languageCode:"ne"}}});
          if(this.target==="ko") this._emit({serverContent:{outputTranscription:{text:"새끼 두 마리가 약합니다",languageCode:"ko"},modelTurn:{parts:[{inlineData:{data:PCM}}]}}});
          this._emit({serverContent:{turnComplete:true}});
        }
      }
    }
    _emit(o){ const b=new Blob([JSON.stringify(o)]); setTimeout(()=>this.onmessage && this.onmessage({data:b}),5); }
    close(){ this.readyState=3; }
  }
  window.WebSocket = FakeWS;
})();
"""
res={}
with sync_playwright() as p:
    b=p.chromium.launch(args=["--use-fake-device-for-media-stream","--use-fake-ui-for-media-stream","--autoplay-policy=no-user-gesture-required"])
    ctx=b.new_context(viewport={"width":390,"height":844},device_scale_factor=2,permissions=["microphone"])
    ctx.add_init_script(FAKE_WS)
    ctx.add_init_script("localStorage.setItem('sjint:settings',JSON.stringify({gkey:'AIzaTEST',key:'',model:'auto',auto:false}))")
    pg=ctx.new_page(); errs=[]; pg.on("pageerror",lambda e:errs.append(str(e)))
    pg.route("https://openrouter.ai/**",lambda r:r.fulfill(status=200,body='{"data":[]}',headers={"access-control-allow-origin":"*"}))
    pg.goto("http://localhost:8765/index.html?t=live"); pg.wait_for_timeout(800)
    pg.screenshot(path=OUT+"/01-idle.png")
    pg.click("#live-btn"); pg.wait_for_timeout(600)
    res["label_on"]=pg.text_content("#live-label")
    res["sessions"]=pg.evaluate("window.__ws.map(w=>({target:w.target,model:w.setup&&w.setup.model,echo:w.setup&&w.setup.generationConfig.translationConfig.echoTargetLanguage,keyInUrl:w.url.includes('key=AIzaTEST')}))")
    pg.screenshot(path=OUT+"/02-live.png")
    pg.wait_for_timeout(2500)
    res["audio_sent"]=pg.evaluate("window.__ws.map(w=>w.audioCount||0)")
    res["audio_mime"]=pg.evaluate("(()=>{const m=window.__ws[0].sent.find(x=>x.realtimeInput);return m&&m.realtimeInput.audio.mimeType})()")
    res["chunk_bytes"]=pg.evaluate("(()=>{const m=window.__ws[0].sent.find(x=>x.realtimeInput);return m&&atob(m.realtimeInput.audio.data).length})()")
    res["log"]=pg.evaluate("Array.from(document.querySelectorAll('#feed .msg')).map(e=>e.className+' | '+e.querySelector('.orig').textContent+' => '+e.querySelector('.tr').textContent)")
    pg.screenshot(path=OUT+"/03-logged.png")
    pg.click("#live-btn"); pg.wait_for_timeout(300)
    res["label_off"]=pg.text_content("#live-label")
    res["errors"]=errs
    b.close()
print(json.dumps(res,ensure_ascii=False,indent=1))
