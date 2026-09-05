// Real Android captures framed in a native HTML/SVG composition.
// The ridge geometry and palette follow the existing iOS App Store artwork.
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(root,'out'); await fs.mkdir(out,{recursive:true});
const browser = await chromium.launch({headless:true, ...(process.env.CHROMIUM_PATH ? {executablePath: process.env.CHROMIUM_PATH} : {})});
const page = await browser.newPage({viewport:{width:1080,height:1920},deviceScaleFactor:1});
function ridges(w,h,phase) {
 const layers = [[.70,.34,'#9FAEC2',.7],[.79,.31,'#5F7086',1],[.88,.27,'#2B3038',1.3]];
 return `<svg class="mountains" viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg">`+layers.map(([base,amp,color,jag],i)=>{
  const p=phase+i*1.9,pts=[];
  for(let x=0;x<=w+8;x+=8){const u=x/w,edge=.30+.70*(2*u-1)**2,wave=Math.sin(u*9.2+p)*.45+Math.sin(u*23+p*1.7)*.20*jag+Math.sin(u*47+p*2.3)*.08*jag;pts.push(`${x},${h*base-h*amp*edge*(.75+wave*.25)}`)}
  return `<polygon points="${pts.join(' ')} ${w},${h} 0,${h}" fill="${color}"/>`+(i===2?`<polyline points="${pts.join(' ')}" fill="none" stroke="#318CE7" stroke-width="5"/>`:'')
 }).join('')+'</svg>';
}
const iconXml=await fs.readFile(path.join(root,'../app/src/main/res/drawable/ic_launcher_foreground.xml'),'utf8');
const iconPaths=[...iconXml.matchAll(/android:fillColor="#FF([A-F0-9]+)" android:pathData="([^"]+)"/g)].map(m=>`<path fill="#${m[1]}" d="${m[2]}"/>`).join('');
const icon=`<svg viewBox="18 18 72 72" xmlns="http://www.w3.org/2000/svg"><defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop stop-color="#E3E6EB"/><stop offset="1" stop-color="#C3C9D2"/></linearGradient></defs><path fill="url(#bg)" d="M18 18H90V90H18Z"/>${iconPaths}</svg>`;
const style=`<style>*{box-sizing:border-box}body{margin:0;color:#2B3038;background:linear-gradient(#F7F8FA,#D6DFE9);font-family:'Avenir Next',Arial,sans-serif}.frame{position:relative;width:1080px;height:1920px;overflow:hidden}.mountains{position:absolute;inset:0;width:100%;height:100%}.headline{position:absolute;left:50px;right:50px;top:80px;margin:0;font-size:86px;line-height:1.12;font-weight:900;text-align:center;letter-spacing:-2.5px}.line{display:block;white-space:nowrap}.blue{color:#1E6FC4}.phone{position:absolute;top:360px;left:207px;width:666px;border:8px solid #12141A;border-radius:60px;background:#12141A;box-shadow:0 22px 60px #1E263452;overflow:hidden}.phone img{display:block;width:650px;height:auto}.feature{width:1024px;height:500px;position:relative;overflow:hidden}.feature h1{position:absolute;left:65px;top:72px;margin:0;font-weight:900;font-size:76px;letter-spacing:-2px;line-height:1.1}.feature p{position:absolute;left:70px;top:185px;margin:0;font-weight:600;font-size:27px;line-height:1.4}.feature .mark{position:absolute;right:73px;top:85px;width:255px;height:255px;border-radius:52px;overflow:hidden;box-shadow:0 16px 40px #1e263428}</style>`;
const shots=[
 ['01_today.png',['your routine,','<span class="blue">one tap</span> from pulling'],'01_one_tap.png',.4],
 ['02_working.png',['the clock runs only','<span class="blue">while you hold</span>'],'02_only_while_you_hold.png',1.6],
 ['03_rest.png',['new grip?','<span class="blue">you’ll see it coming</span>'],'03_grip_changes.png',2.8],
 ['04_builder.png',['build it once,','<span class="blue">train it daily</span>'],'04_build_it_once.png',4.1],
 ['05_history.png',['every pull,','<span class="blue">part of your progress</span>'],'05_your_progress.png',5.3],
 ['06_share_qr.png',['the code <span class="blue">is</span>','the routine'],'06_the_code_is_the_routine.png',6.6]
];
for(const [raw,lines,name,phase] of shots){
 let b;try{b=await fs.readFile(path.join(root,'raw',raw))}catch{continue}
 const html=`<!doctype html><html lang="en"><meta charset="utf-8">${style}<div class="frame">${ridges(1080,1920,phase)}<h1 class="headline">${lines.map(x=>`<span class="line">${x}</span>`).join('')}</h1><div class="phone"><img alt="Get a Grip Android screenshot" src="data:image/png;base64,${b.toString('base64')}"></div></div></html>`;
 await page.setViewportSize({width:1080,height:1920});await page.setContent(html);await page.evaluate(async()=>{await document.fonts.ready;await Promise.all([...document.images].map(i=>i.decode()));let e=document.querySelector('.headline'),s=86;while([...e.children].some(x=>x.scrollWidth>980)&&s>55)e.style.fontSize=`${--s}px`});
 await page.screenshot({path:path.join(out,name),type:'png'});console.log(name);
}
await page.setViewportSize({width:1024,height:500});
await page.setContent(`<!doctype html><html><meta charset="utf-8">${style}<div class="feature">${ridges(1024,500,.4)}<h1>Get a Grip</h1><p>Your daily finger training.</p><div class="mark">${icon}</div></div></html>`);
await page.screenshot({path:path.join(out,'feature-graphic.png')});
await page.setViewportSize({width:512,height:512});await page.setContent(`<html><style>body{margin:0}svg{display:block;width:512px;height:512px}</style>${icon}</html>`);await page.screenshot({path:path.join(out,'icon-512.png')});
await browser.close();
