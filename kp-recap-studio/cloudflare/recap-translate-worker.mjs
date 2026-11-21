/* KP Recap Studio translation Worker.
 * Deploy separately with Cloudflare Workers AI binding `AI`.
 * No account API tokens in the Android APK. No video or PDF files are uploaded;
 * only small user-selected transcript snippets.
 * IMPORTANT: Worker is NOT deployed until access credentials are restored.
 */
const limits = new Map();
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ok:true,service:"kp-recap-translate",backend:"m2m100"},200);
    }
    if (request.method !== "POST" || url.pathname !== "/translate")
      return json({error:"Not found"},404);
    if (!env.AI) return json({error:"Workers AI binding not configured"},503);
    const length = Number(request.headers.get("content-length")||0);
    if (length > 2500) return json({error:"Request too large"},413);
    const ip = request.headers.get("cf-connecting-ip")||"unknown";
    const now=Date.now(),minute=Math.floor(now/60000),entry=limits.get(ip);
    const hit=entry?.minute===minute?entry.count+1:1;
    limits.set(ip,{minute,count:hit});
    if (limits.size>1000) limits.clear();
    if (hit>12) return json({error:"Rate limit; retry after a minute"},429);
    let body;
    try {body=await request.json();}catch{return json({error:"Invalid JSON"},400);}
    const source_lang=String(body?.source_lang||"en").toLowerCase();
    const text=String(body?.text||"").trim();
    if (!/^[a-z]{2,3}$/.test(source_lang)||text.length<1||text.length>600)
      return json({error:"Invalid language or text (1-600 chars)"},400);
    if(source_lang==="my") return json({translated_text:text,source_lang,target_lang:"my"},200);
    try {
      const result = await env.AI.run("@cf/meta/m2m100-1.2b",
        {text, source_lang, target_lang:"my"});
      const translated_text = String(result?.translated_text || "").trim();
      if(!translated_text) return json({error:"Translation model returned no text"},502);
      return json({translated_text,source_lang,target_lang:"my"},200);
    } catch(e) {
      return json({error:"AI translation temporarily unavailable",detail:String(e?.message||"")},503);
    }
  }
};
function json(body,status) {
  return new Response(JSON.stringify(body),{
    status,
    headers:{"content-type":"application/json; charset=utf-8","cache-control":"no-store"}
  });
}
