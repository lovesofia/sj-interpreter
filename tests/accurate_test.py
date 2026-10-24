"""E2E: 정확 모드 — 대표 지시는 용어집 번역 + Gemini 음성, 직원 말은 즉시 통역"""
import json,sys,base64
from playwright.sync_api import sync_playwright
OUT=sys.argv[1]
FAKE_WS = r"""
(() => {
  const PCM = btoa(String.fromCharCode(...new Uint8Array(4800)));
  window.__ws=[]; window.__played=0;
  class FakeWS {
    constructor(url){ this.url=url; this.readyState=0; this.sent=[]; window.__ws.push(this); setTimeout(()=>{this.readyState=1;this.onopen&&this.onopen()},30) }
    send(d){ const m=JSON.parse(d);
      if(m.setup){ if('inputAudioTranscription' in m.setup.generationConfig || !('inputAudioTranscription' in m.setup)){ this.onclose&&this.onclose({code:1007,reason:'Invalid JSON payload received. Unknown name "inputAudioTranscription"'}); return; } this.target=m.setup.generationConfig.translationConfig.targetLanguageCode; setTimeout(()=>this._emit({setupComplete:{}}),30); return }
      if(m.realtimeInput){ this.n=(this.n||0)+1;
        if(this.n===3){ this._emit({serverContent:{inputTranscription:{text:"3번 분만틀 보온등 켜고 자돈 이유 준비해",languageCode:"ko"}}});
          if(this.target!=="ko") this._emit({serverContent:{outputTranscription:{text:"(live) ...",languageCode:this.target},modelTurn:{parts:[{inlineData:{data:PCM}}]}}});
          this._emit({serverContent:{turnComplete:true}}) }
      } }
    _emit(o){ setTimeout(()=>this.onmessage&&this.onmessage({data:new Blob([JSON.stringify(o)])}),5) }
    close(){ this.readyState=3 }
  }
  window.WebSocket=FakeWS;
})();
"""
pcm=base64.b64encode(bytes(9600)).decode()
calls={"or":[], "tts":[]}
def orr(route):
    calls["or"].append(json.loads(route.request.post_data)["messages"][0]["content"])
    body={"choices":[{"message":{"content":json.dumps({"source":"ko","translation":"3-sonli tug‘ish kletkasidagi isitish chirog‘ini yoqing va cho‘chqa bolalarini sutdan ajratishga tayyorlang","back_ko":"3번 분만틀의 보온등을 켜고 자돈 이유를 준비하세요","terms":["분만틀","보온등","자돈","이유"],"check":""},ensure_ascii=False)}}]}
    route.fulfill(status=200,content_type="application/json",headers={"access-control-allow-origin":"*"},body=json.dumps(body))
def tts(route):
    calls["tts"].append(route.request.url.split("?")[0])
    route.fulfill(status=200,content_type="application/json",headers={"access-control-allow-origin":"*"},body=json.dumps({"steps":[{"type":"model_output","content":[{"type":"audio","data":pcm}]}]}))
with sync_playwright() as p:
    b=p.chromium.launch(args=["--use-fake-device-for-media-stream","--use-fake-ui-for-media-stream","--autoplay-policy=no-user-gesture-required"])
    ctx=b.new_context(viewport={"width":390,"height":844},device_scale_factor=2,permissions=["microphone"])
    ctx.add_init_script(FAKE_WS)
    ctx.add_init_script("localStorage.clear();localStorage.setItem('sjint:settings',JSON.stringify({gkey:'AIzaT',key:'sk-or-v1-t',model:'auto',auto:false,accurate:false}));localStorage.setItem('sjint:workers',JSON.stringify([{id:'w2',name:'자수르',lang:'uz'}]));localStorage.setItem('sjint:current',JSON.stringify('w2'))")
    pg=ctx.new_page(); errs=[]; pg.on("pageerror",lambda e:errs.append(str(e)))
    pg.route("https://openrouter.ai/api/v1/models",lambda r:r.fulfill(status=200,body='{"data":[]}',headers={"access-control-allow-origin":"*"}))
    pg.route("https://openrouter.ai/api/v1/chat/completions",orr)
    pg.route("https://generativelanguage.googleapis.com/**",tts)
    pg.goto("http://localhost:8765/index.html?t=acc"); pg.wait_for_timeout(600)
    pg.click("#acc-btn"); r={"acc_on":pg.get_attribute("#acc-btn","aria-pressed"),"hint":pg.text_content("#acc-hint")}
    pg.click("#live-btn"); pg.wait_for_timeout(1500)
    r["log"]=pg.evaluate("Array.from(document.querySelectorAll('#feed .msg')).map(e=>({cls:e.className,tr:e.querySelector('.tr').textContent,back:(e.querySelector('.back')||{}).textContent,chips:Array.from(e.querySelectorAll('.chip')).map(c=>c.textContent)}))")
    r["glossary_in_prompt"]="tug‘ish kletkasi" in (calls["or"][0] if calls["or"] else "")
    r["tts_calls"]=calls["tts"]; r["errors"]=errs
    pg.screenshot(path=OUT+"/a1-accurate.png")
    b.close()
print(json.dumps(r,ensure_ascii=False,indent=1))
