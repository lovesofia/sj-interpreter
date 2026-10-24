"""E2E: 처음 보는 사람 모드 — 언어 자동 감지 후 양방향 연결"""
import json,sys
from playwright.sync_api import sync_playwright
OUT=sys.argv[1]
FAKE_WS = r"""
(() => {
  const PCM = btoa(String.fromCharCode(...new Uint8Array(4800)));
  window.__ws = [];
  class FakeWS {
    constructor(url){ this.url=url; this.readyState=0; this.sent=[]; window.__ws.push(this);
      setTimeout(()=>{ this.readyState=1; this.onopen && this.onopen(); },30); }
    send(d){ const m=JSON.parse(d); this.sent.push(m);
      if(m.setup){ if('inputAudioTranscription' in m.setup.generationConfig || !('inputAudioTranscription' in m.setup)){ this.onclose&&this.onclose({code:1007,reason:'Invalid JSON payload received. Unknown name "inputAudioTranscription"'}); return; } this.target=m.setup.generationConfig.translationConfig.targetLanguageCode; setTimeout(()=>this._emit({setupComplete:{}}),30); return; }
      if(m.realtimeInput){ this.n=(this.n||0)+1;
        if(this.target==="ko" && this.n===8){   // 상대가 우즈베크어로 먼저 말함
          this._emit({serverContent:{inputTranscription:{text:"Assalomu alaykum, men ishga keldim",languageCode:"uz-UZ"}}});
          this._emit({serverContent:{outputTranscription:{text:"안녕하세요, 일하러 왔습니다",languageCode:"ko"},modelTurn:{parts:[{inlineData:{data:PCM}}]}}});
          this._emit({serverContent:{turnComplete:true}});
        }
        if(this.target==="uz" && this.n===4){   // 대표가 한국어로 대답
          this._emit({serverContent:{inputTranscription:{text:"반갑습니다 장화 신고 따라오세요",languageCode:"ko"}}});
          this._emit({serverContent:{outputTranscription:{text:"Xush kelibsiz, etik kiyib orqamdan yuring",languageCode:"uz"},modelTurn:{parts:[{inlineData:{data:PCM}}]}}});
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
    ctx.add_init_script("if(!sessionStorage.getItem('x')){sessionStorage.setItem('x',1);localStorage.clear();localStorage.setItem('sjint:settings',JSON.stringify({gkey:'AIzaTEST',key:'',model:'auto',auto:false}))}")
    pg=ctx.new_page(); errs=[]; pg.on("pageerror",lambda e:errs.append(str(e)))
    pg.route("https://openrouter.ai/**",lambda r:r.fulfill(status=200,body='{"data":[]}',headers={"access-control-allow-origin":"*"}))
    pg.goto("http://localhost:8765/index.html?t=guest"); pg.wait_for_timeout(700)
    pg.screenshot(path=OUT+"/g0-idle.png")
    pg.click(".tag.first"); pg.wait_for_timeout(500)
    res["sessions_before"]=pg.evaluate("window.__ws.map(w=>w.target)")
    res["greet_visible"]=pg.is_visible("#greet")
    res["label_before"]=pg.text_content("#live-label")
    pg.screenshot(path=OUT+"/g1-detecting.png")
    pg.wait_for_timeout(1400)
    res["sessions_after"]=pg.evaluate("window.__ws.map(w=>w.target)")
    res["note"]=pg.text_content("#live-text")
    res["greet_after"]=pg.is_visible("#greet")
    res["tag"]=pg.text_content(".tag[aria-pressed=true]")
    pg.wait_for_timeout(900)
    res["log"]=pg.evaluate("Array.from(document.querySelectorAll('#feed .msg')).map(e=>e.className.split(' ')[1]+' | '+e.querySelector('.who').textContent+' | '+e.querySelector('.tr').textContent)")
    pg.screenshot(path=OUT+"/g2-talking.png")
    pg.click("#live-btn"); pg.wait_for_timeout(300)
    res["name_sheet"]=pg.is_visible("#wsheet") and pg.text_content("#wsheet h2")
    pg.fill("#w-name","자수르"); pg.click("#wform button[type=submit]"); pg.wait_for_timeout(200)
    res["saved"]=pg.evaluate("JSON.parse(localStorage.getItem('sjint:workers')).map(w=>w.name+':'+w.lang+(w.guest?':guest':''))")
    pg.reload(); pg.wait_for_timeout(500)
    res["after_reload_tag"]=pg.text_content(".tag[aria-pressed=true]")
    # 감지 전에 끝내면 임시 상대가 사라져야 함
    pg.click(".tag.first"); pg.wait_for_timeout(300); pg.click("#live-btn"); pg.wait_for_timeout(200)
    res["no_leftover"]=pg.evaluate("JSON.parse(localStorage.getItem('sjint:workers')).filter(w=>w.lang==='auto').length")
    res["errors"]=errs
    b.close()
print(json.dumps(res,ensure_ascii=False,indent=1))
