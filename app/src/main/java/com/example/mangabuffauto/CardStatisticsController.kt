package com.example.mangabuffauto

import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * MangaBuff Card Statistics Android integration.
 *
 * Based on the public MangaBuff Card Statistics userscript architecture:
 * - batch mbstat.space requests (up to 200 ids)
 * - MangaBuff pagination fallback
 * - 24h local cache
 *
 * Important: WebView JS never creates one global callback per request anymore.
 * A single dispatcher receives requestId -> response, which avoids the
 * window.__mbCardStatsCb_* race seen in V3/V4.
 */
class CardStatisticsController(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("card_statistics", Context.MODE_PRIVATE)
    private val executor = Executors.newFixedThreadPool(4)
    private val attachedWebViews = Collections.newSetFromMap(WeakHashMap<WebView, Boolean>())
    private val navigationGeneration = AtomicLong(0L)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        private set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    fun setEnabled(value: Boolean, webView: WebView?) {
        enabled = value
        val wv = webView ?: return
        if (value) {
            val wasAttached = attachedWebViews.contains(wv)
            wv.addJavascriptInterface(NativeBridge(wv), BRIDGE_NAME)
            attachedWebViews.add(wv)
            if (wasAttached) {
                inject(wv)
            } else {
                // Android exposes addJavascriptInterface objects to page JavaScript
                // after the next page load. The page is already loaded when the user
                // presses 🃏, so reload once to make the native bridge visible.
                wv.reload()
            }
        } else {
            wv.evaluateJavascript(DISABLE_JS, null)
        }
    }

    fun onPageStarted(webView: WebView) {
        navigationGeneration.incrementAndGet()
    }

    fun onPageFinished(webView: WebView) {
        if (!enabled) return
        if (!attachedWebViews.contains(webView)) {
            webView.addJavascriptInterface(NativeBridge(webView), BRIDGE_NAME)
            attachedWebViews.add(webView)
            // The bridge becomes visible to JavaScript on the next page load.
            webView.reload()
            return
        }
        inject(webView)
    }

    fun clear(webView: WebView?) {
        webView?.evaluateJavascript(DISABLE_JS, null)
    }

    private fun inject(webView: WebView) {
        if (!attachedWebViews.contains(webView)) {
            webView.addJavascriptInterface(NativeBridge(webView), BRIDGE_NAME)
            attachedWebViews.add(webView)
            // Do not evaluate ENABLE_JS before the bridge is available to page JS.
            return
        }
        webView.evaluateJavascript(ENABLE_JS, null)
    }

    private inner class NativeBridge(private val webView: WebView) {
        @JavascriptInterface
        fun get(url: String, requestId: String) {
            val requestGeneration = navigationGeneration.get()
            executor.execute {
                var body = ""
                var status = 0
                var error = ""
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 10000
                        readTimeout = 10000
                        instanceFollowRedirects = true
                        setRequestProperty("Accept", "application/json,text/plain,*/*")
                        setRequestProperty("User-Agent", WEBVIEW_UA)
                        setRequestProperty("Referer", "https://mangabuff.ru/")
                        setRequestProperty("Origin", "https://mangabuff.ru")

                        // The original userscript can access MangaBuff through the
                        // browser session. Reuse the WebView's cookies for fallback
                        // requests to mangabuff.ru.
                        if (url.startsWith("https://mangabuff.ru/")) {
                            CookieManager.getInstance().getCookie("https://mangabuff.ru")
                                ?.takeIf { it.isNotBlank() }
                                ?.let { setRequestProperty("Cookie", it) }
                        }
                    }
                    status = connection.responseCode
                    val stream = if (status in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) error = "HTTP $status"
                } catch (e: Exception) {
                    error = e.javaClass.simpleName + ":" + (e.message ?: "")
                } finally {
                    connection?.disconnect()
                }

                val payload = org.json.JSONObject().apply {
                    put("id", requestId)
                    put("status", status)
                    put("body", body)
                    put("error", error)
                }
                val escaped = org.json.JSONObject.quote(payload.toString())
                webView.post {
                    if (requestGeneration != navigationGeneration.get()) return@post
                    val js = "(function(){try{if(typeof window.__mbCardStatsNativeResult !== 'function')return;window.__mbCardStatsNativeResult($escaped);}catch(e){}})();"
                    webView.evaluateJavascript(js, null)
                }
            }
        }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val BRIDGE_NAME = "MangaBuffCardStatsNative"
        private const val WEBVIEW_UA = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/153.0 Mobile Safari/537.36 MangaBuffAuto/11.1"

        private val DISABLE_JS = """
            (function(){
              try{
                window.__mbCardStatsEnabled=false;
                if(window.__mbCardStatsObserver){window.__mbCardStatsObserver.disconnect();window.__mbCardStatsObserver=null;}
                if(window.__mbCardStatsTimer){clearTimeout(window.__mbCardStatsTimer);window.__mbCardStatsTimer=null;}
                document.querySelectorAll('.mb-card-stats-overlay').forEach(function(e){e.remove();});
                document.querySelectorAll('.mb-card-stats-host').forEach(function(e){e.classList.remove('mb-card-stats-host');});
                var s=document.getElementById('mb-card-stats-style');if(s)s.remove();
              }catch(e){}
            })();
        """.trimIndent()

        private val ENABLE_JS = """
          (function(){
            'use strict';
            if(window.__mbCardStatsEnabled)return;
            window.__mbCardStatsEnabled=true;

            var API='https://mbstat.space';
            var SITE='https://mangabuff.ru';
            var CACHE_TTL=24*60*60*1000;
            var CACHE_PREFIX='mangabuff_card_stats_cache_v203_android_v5_';
            var pendingCards={};
            var scanTimer=null;

            if(!document.getElementById('mb-card-stats-style')){
              var st=document.createElement('style');st.id='mb-card-stats-style';
              st.textContent='.mb-card-stats-host{position:relative!important}'+
                '.mb-card-stats-overlay{position:absolute;left:4px;bottom:4px;background:rgba(0,0,0,.85);color:#fff;padding:4px 6px;border-radius:3px;font:700 9px/1.3 Arial,sans-serif;z-index:9999;pointer-events:none;white-space:nowrap;backdrop-filter:blur(5px)}'+
                '.mb-card-stats-row{display:flex;justify-content:space-between;gap:7px;margin:1px 0}.mb-card-stats-label{color:#aaa;font-size:8px}.mb-card-stats-value{font-size:9px}.mb-card-stats-value.owners{color:#4ade80}.mb-card-stats-value.wanters{color:#fb923c}.mb-card-stats-loading{color:#888;font-style:italic}';
              document.head.appendChild(st);
            }

            function cacheGet(id){
              try{var x=JSON.parse(localStorage.getItem(CACHE_PREFIX+id)||'null');return x&&x.timestamp&&Date.now()-x.timestamp<CACHE_TTL?x:null}catch(e){return null}
            }
            function cacheSet(id,x){try{localStorage.setItem(CACHE_PREFIX+id,JSON.stringify({owners:x.owners,wanters:x.wanters,timestamp:Date.now()}))}catch(e){}}
            function render(host,id,data){
              if(!host||!document.contains(host))return;
              var el=host.querySelector('.mb-card-stats-overlay');
              if(!el){el=document.createElement('div');el.className='mb-card-stats-overlay';host.appendChild(el)}
              el.innerHTML='<div class="mb-card-stats-row"><span class="mb-card-stats-label">Владеют</span><span class="mb-card-stats-value owners">'+(data&&data.owners!=null?data.owners:'—')+'</span></div>'+
                '<div class="mb-card-stats-row"><span class="mb-card-stats-label">Желают</span><span class="mb-card-stats-value wanters">'+(data&&data.wanters!=null?data.wanters:'—')+'</span></div>';
            }

            // One permanent response dispatcher. No window.__mbCardStatsCb_* callbacks.
            var bridgeSeq=0, bridgePending={}, bridgeActive=0, bridgeQueue=[], BRIDGE_MAX=4;
            window.__mbCardStatsNativeResult=function(raw){
              try{
                var x=typeof raw==='string'?JSON.parse(raw):raw;
                if(!x||!x.id)return;
                var item=bridgePending[x.id];
                if(!item)return;
                delete bridgePending[x.id];
                bridgeActive=Math.max(0,bridgeActive-1);
                item.done(x.body||'',Number(x.status||0),x.error||'');
                pumpBridge();
              }catch(e){console.error('[MangaBuff Stats][Android] dispatcher error',e)}
            };
            function pumpBridge(){
              while(bridgeActive<BRIDGE_MAX&&bridgeQueue.length){
                var item=bridgeQueue.shift();
                bridgeActive++;
                bridgePending[item.id]=item;
                try{window.MangaBuffCardStatsNative.get(item.url,item.id)}catch(e){
                  delete bridgePending[item.id];bridgeActive=Math.max(0,bridgeActive-1);item.done('',0,String(e));
                }
              }
            }
            function bridge(url,done){
              var id='r'+(++bridgeSeq)+'_'+Date.now();
              bridgeQueue.push({id:id,url:url,done:done});
              pumpBridge();
              setTimeout(function(){
                var p=bridgePending[id];
                if(!p)return;
                delete bridgePending[id];bridgeActive=Math.max(0,bridgeActive-1);
                p.done('',0,'timeout');pumpBridge();
              },15000);
            }

            function request(url,done,retry){
              retry=retry||0;
              bridge(url,function(body,status,error){
                if(status===429&&retry<3){
                  setTimeout(function(){request(url,done,retry+1)},2000*(retry+1));
                  return;
                }
                done(body,status,error);
              });
            }

            function maxPage(html){
              try{
                var d=new DOMParser().parseFromString(html,'text/html'),max=1;
                d.querySelectorAll('.pagination__button a[href*="page="]').forEach(function(a){
                  var m=(a.getAttribute('href')||'').match(/page=(\d+)/);
                  if(m)max=Math.max(max,parseInt(m[1],10));
                });
                return max;
              }catch(e){return null}
            }

            function fallback(id,done){
              var owners=null,wanted=null,finished=false;
              function check(){if(finished)return;if(owners!==null&&wanted!==null){finished=true;done({owners:owners,wanters:wanted})}}
              request(SITE+'/cards/'+id+'/users',function(html,status,error){
                owners=(status>=200&&status<300&&html)?maxPage(html):null;
                check();
              });
              request(SITE+'/cards/'+id+'/offers/want',function(html,status,error){
                wanted=(status>=200&&status<300&&html)?maxPage(html):null;
                check();
              });
            }

            function parseBatch(body){
              try{
                var arr=JSON.parse(body);
                if(!Array.isArray(arr))return {};
                var map={};
                arr.forEach(function(v){if(v&&v.id!=null)map[String(v.id)]=v});
                return map;
              }catch(e){return null}
            }

            function finishCard(id,host,data){
              if(data&&data.owners!=null&&data.wanters!=null){
                var normalized={owners:Number(data.owners),wanters:Number(data.wanters)};
                cacheSet(id,normalized);render(host,id,normalized);
              }else{
                render(host,id,null);
              }
              delete pendingCards[id];
            }

            function processBatch(ids,hosts){
              if(!ids.length)return;
              var url=API+'/cards?ids='+ids.join(',');
              request(url,function(body,status,error){
                var map=parseBatch(body);
                if(map===null){console.warn('[MangaBuff Stats][Android] batch JSON failed status='+status+' error='+error);map={}}
                var missing=[];
                ids.forEach(function(id){
                  var host=hosts[id];
                  var v=map[String(id)];
                  if(v&&v.owners!=null&&v.wanted!=null){
                    finishCard(id,host,{owners:v.owners,wanters:v.wanted});
                  }else{
                    missing.push(id);
                  }
                });
                missing.forEach(function(id){
                  fallback(id,function(data){finishCard(id,hosts[id],data)})
                });
              });
            }

            function idOf(card){return card.getAttribute('data-card-id')||card.getAttribute('data-id')}
            function hostOf(card){var h=card.closest('.manga-cards__item-wrapper')||card;h.classList.add('mb-card-stats-host');return h}

            function scan(){
              if(!window.__mbCardStatsEnabled)return;
              var ids=[],hosts={};
              document.querySelectorAll('.manga-cards__item[data-card-id],.lootbox__card[data-id],.deck__item[data-card-id]').forEach(function(card){
                var id=idOf(card),host=hostOf(card);
                if(!id||!host||host.querySelector('.mb-card-stats-overlay')||pendingCards[id])return;
                var cached=cacheGet(id);
                if(cached){render(host,id,cached);return}
                render(host,id,null);
                pendingCards[id]=true;
                ids.push(String(id));hosts[String(id)]=host;
              });
              // Match the userscript: one backend request for up to 200 card ids.
              for(var i=0;i<ids.length;i+=200)processBatch(ids.slice(i,i+200),hosts);
            }

            window.__mbCardStatsObserver=new MutationObserver(function(){
              clearTimeout(scanTimer);scanTimer=setTimeout(scan,350);
            });
            window.__mbCardStatsObserver.observe(document.documentElement||document.body,{childList:true,subtree:true,attributes:true,attributeFilter:['data-id','data-card-id']});
            scan();
          })();
        """.trimIndent()
    }
}
