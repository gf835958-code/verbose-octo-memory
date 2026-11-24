/**
 * KP Recap Studio translation backend.
 * Worker AI binding: @cf/meta/m2m100-1.2b (supports Burmese "my").
 * The Android APK never stores a Cloudflare API token.
 * This is a staging worker, not automatically exposed on workers.dev.
 */
const MODEL="@cf/meta/m2m100-1.2b";
const allowed=new Set(["af","am","ar","az","be","bg","bn","ca","cs","cy","da","de","el","en","es","et","fa","fi","fr","ga","gu","ha","he","hi","hr","hu","id","is","it","ja","jv","ka","kk","km","kn","ko","lo","lt","lv","mg","mk","ml","mn","mr","ms","my","ne","nl","no","pa","pl","ps","pt","ro","ru","si","sk","sl","so","sq","sr","sv","sw","ta","te","th","tl","tr","uk","ur","uz","vi","zh"]);
const buckets=new Map();
function reply(data,status=200){return new Response(JSON.stringify(data),{status,headers:{"content-type":"application/json; charset=utf-8","cache-control":"no-store","x-content-type-options":"nosniff"}});}
export default {
 async fetch(request,env){
   const u=new URL(request.url);
   if(request.method==="GET" && u.pathname==="/health")
      return reply({service:"KP Recap translation",enabled:!!env.AI,model:MODEL});
   if(u.pathname!=="/translate")return reply({error:"Not found"},404);
   if(request.method!=="POST")return reply({error:"POST required"},405);
   if(!env.AI)return reply({error:"AI binding not configured"},503);
   if(!(request.headers.get("content-type")||"").toLowerCase().startsWith("application/json"))
     return reply({error:"Use application/json"},415);
   const length=Number(request.headers.get("content-length")||0);
   if(length>2500)return reply({error:"Request too large"},413);
   const ip=request.headers.get("cf-connecting-ip")||"unknown";
   const now=Date.now(), bucket=buckets.get(ip)||{start:now,count:0};
   if(now-bucket.start>60000){bucket.start=now;bucket.count=0;}
   bucket.count++;buckets.set(ip,bucket);
   if(buckets.size>3000)for(const [key,value] of buckets)if(now-value.start>60000)buckets.delete(key);
   // In-isolate throttle only. Apply Cloudflare account-level rate limits in production.
   if(bucket.count>6)return reply({error:"Rate limited; please retry later"},429);
   let data;
   try {
     const raw=await request.text();
     if(new TextEncoder().encode(raw).byteLength>2500)return reply({error:"Request too large"},413);
     data=JSON.parse(raw);
   }catch(e){return reply({error:"Invalid JSON"},400);}
   const text=typeof data.text==="string"?data.text.trim():"";
   const source=typeof data.source_lang==="string"?data.source_lang.trim().toLowerCase():"";
   if(!text||text.length>850)return reply({error:"text must be 1–850 characters"},422);
   if(!allowed.has(source))return reply({error:"Unsupported source language"},422);
   if(data.target_lang!=="my")return reply({error:"Only Burmese target supported"},422);
   if(source==="my")return reply({translated_text:text});
   try{
     const result=await env.AI.run(MODEL,{text,source_lang:source,target_lang:"my"});
     const translated=(result?.translated_text||result?.translation||"").trim();
     if(!translated)return reply({error:"Translation model returned an empty result"},502);
     return reply({translated_text:translated});
   }catch(e){
     return reply({error:"Cloudflare AI translation unavailable or daily quota exhausted"},503);
   }
 }
};
