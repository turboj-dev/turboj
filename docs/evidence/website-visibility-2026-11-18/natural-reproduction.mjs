import { chromium } from '/Users/luca/Workspace/turboj/website/node_modules/playwright/index.mjs';
const browser=await chromium.launch({executablePath:'/Users/luca/Library/Caches/ms-playwright/chromium_headless_shell-1248/chrome-headless-shell-mac-arm64/chrome-headless-shell',headless:true});
const context=await browser.newContext({viewport:{width:320,height:844},reducedMotion:'no-preference'});
const page=await context.newPage();
await page.addInitScript(()=>{
 const Native=window.IntersectionObserver;window.__io=[];window.__observations=[];window.__held=[];window.__hold=false;
 window.IntersectionObserver=class extends Native{
 constructor(callback,options){let observer;super((entries,self)=>{const rows=entries.map(e=>({time:e.time,ratio:e.intersectionRatio,intersecting:e.isIntersecting,target:e.target.className}));window.__observations.push({at:performance.now(),rows});if(entries.some(e=>e.target.matches('.demo-stage'))){window.__deliver=()=>callback(window.__held.splice(0),self);if(window.__hold){window.__held.push(...entries);return;}}callback(entries,self)},options);}
 };
});
await page.goto('http://127.0.0.1:4322');await page.waitForFunction(()=>document.querySelector('[data-turboj-demo]')?.dataset.loading==='false');
const state=()=>page.evaluate(()=>{const r=document.querySelector('[data-turboj-demo]'),b=document.querySelector('.demo-stage').getBoundingClientRect();return{state:r.dataset.state,chapter:r.dataset.chapter,elapsed:r.dataset.elapsed,manual:r.dataset.manualPaused,rect:{top:b.top,bottom:b.bottom,height:b.height,width:b.width,left:b.left,right:b.right},viewport:{w:innerWidth,h:innerHeight},held:window.__held?.map(e=>({ratio:e.intersectionRatio,time:e.time})),observations:window.__observations.slice(-8)}});
for(let i=0;i<8;i++){
 await page.locator('[data-demo-chapter="0"]').evaluate(b=>b.click());await page.waitForFunction(()=>document.querySelector('[data-turboj-demo]').dataset.loading==='false');
 await page.locator('[data-demo-action="skip"]').evaluate(b=>b.click());
 await page.evaluate(()=>window.scrollTo(0,document.body.scrollHeight));await page.locator('[data-demo-action="pause"]').focus();await page.keyboard.press('Enter');
 await page.waitForTimeout(350);const result=await state();console.log(JSON.stringify({iteration:i,...result}));
 if(result.state!=='playing')break;
}
// Hold genuine browser observations through scroll-away and scroll-back, then deliver one batch.
await page.evaluate(()=>{window.__hold=true;window.scrollTo(0,document.body.scrollHeight)});await page.waitForFunction(()=>window.__held.some(e=>e.intersectionRatio<.15));
await page.locator('.demo-stage').evaluate(e=>e.scrollIntoView({block:'center',behavior:'instant'}));await page.waitForFunction(()=>window.__held.some(e=>e.intersectionRatio>=.15));
console.log('before-batch',JSON.stringify(await state()));await page.evaluate(()=>{window.__hold=false;window.__deliver()});await page.waitForTimeout(250);console.log('after-batch',JSON.stringify(await state()));
await browser.close();
