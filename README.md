# WolfTeam 2012 LAN yaması

Bu depo, 2012 istemcisiyle **yalnız Radmin VPN üzerinden** arkadaşlarla oynamak için gereken yamayı, yerel sunucuyu ve isteğe bağlı envanter yöneticisini içerir. Oyunun kendi dosyaları depoda bulunmaz.

## Oyun dosyalarını indirin

[WolfTeam Türkiye oyun arşivini Archive.org'dan indirin](https://archive.org/details/wolfteamturkiye) ve arşivi bir klasöre çıkarın. Kurulum klasöründe `Wolfteam.exe`, `wolf.xfs` ve `sound.xfs` dosyaları bulunmalı.

## Kurulum

1. GitHub’da **Code → Download ZIP** seçip bu yamayı indirin.
2. Yama ZIP'inin içindeki dosyaları oyun klasörüne çıkarın ve sorulursa var olanlarla birleştirin.
3. Oyun klasöründeki **Kurulum.bat** dosyasına çift tıklayın.
4. Sunucuyu kuracak bilgisayarda **1**; yalnızca arkadaşının sunucusuna bağlanacak bilgisayarda **2** seçin.
5. Kurulum bittiğinde **Y** tuşuna basarsanız oyun doğrudan açılır. Sonradan başlatmak için `WolfteamBaslat.bat` dosyasına çift tıklayın.

Kurulum, oyunun LAN bileşenleri için taşınabilir Python'u indirir; Direct3D uyumluluk dosyaları yama ZIP'inde hazır gelir. Katılımcı bilgisayara Python veya Java kurmak gerekmez. Sunucu kurulurken JDK 17, PHP ve MariaDB oyun klasörünün içindeki `.runtime` dizinine indirilir; sistem PATH'ine eklenmez. Sunucu bilgisayarında envanter yöneticisi için Python/Tk ve Pillow da aynı şekilde yerel klasöre hazırlanır. Envanter yöneticisi başka bir bilgisayarda ilk kez açılırsa gerekli bileşenleri kendi `.runtime` klasörüne indirir. İlk kurulum internet bağlantısı gerektirir. Oyunu başlatırken yerel ağ kuralları için Windows yönetici izni istenir.

Tüm oyuncular Radmin VPN'i açıp aynı Radmin ağına katılsın. Aynı evdeki oyuncular da Radmin kullanır. Sunucu sahibi `WolfteamBaslat.bat` içinde **1** seçer. Sunucu hazır olduğunda oyun klasöründe **RadminKatil.bat** oluşur. Kurucu bu küçük dosyayı arkadaşlarına gönderir; arkadaşları dosyayı `WolfteamBaslat.bat` ile aynı oyun klasörüne koyup çift tıklar. Başlatıcı adres sormadan doğrudan bu sunucuya bağlanır. Yerel oyuncu adı sorulduğunda mevcut adı seçin veya yeni bir ad oluşturun; parola yoktur.

Bu dosya ilk açılışta sunucuyu bilgisayara kaydeder. Sonraki açılışlarda `WolfteamBaslat.bat` içindeki **2** seçeneği kayıtlı sunucuyu listede gösterir. Otomatik Radmin keşfi de çalışır; yayın paketlerini geçirmeyen bağlantılarda katılma dosyasını kullanın. Fiziksel Wi-Fi/Ethernet ağı taranmaz. Sunucunun Radmin adresi değişirse kurucunun yeniden ürettiği katılma dosyasını alın.

`RadminKatil.bat` ve `RadminSunucu.json` bilgisayara özel üretilir, Git'e eklenmez ve public depoda bulunmaz.

Başlatıcı, oyunun güncelleme adresini her oturumda seçilen LAN/Radmin sunucusuna yönlendirir ve oyun kapandıktan sonra dosyanın önceki içeriğini geri yükler. Arkadaş bilgisayarında `127.0.0.1` kendi bilgisayarını gösterdiğinden bu yönlendirme güncelleme denetiminin sunucuya ulaşmasını sağlar.

Hata olursa kurulum penceresini kapatmadan son ekrandaki mesajın fotoğrafını oyun kurucusuna gönderin.

## Envanter yöneticisi

`envanter_yoneticisi.bat`, sunucu bilgisayarındaki hesaplara eşya eklemek için isteğe bağlı araçtır. Sunucu kurulumunda gerekli Python/Tk/Pillow bileşenleri oyun klasörünün `.runtime` dizinine hazırlanır; sisteme Python veya paket kurmanız gerekmez. Paneli sunucu bilgisayarında, sunucu kurulumunu tamamladıktan sonra açın.

## Güncelleme ve içerik

Yeni yama sürümü çıktığında GitHub’daki **Code → Download ZIP** ile güncel kaynakları alın ve oyun klasöründeki dosyalarla birleştirin. Oyun dosyalarını tekrar indirmek gerekmez. Yerel hesaplar ve sunucu verileri `.runtime` klasöründe bu bilgisayarda saklanır; bu klasörü paylaşmayın.

## Lisanslar

Proje kaynakları MIT lisanslıdır. Logan sunucu kodunun özgün lisansı `logan/LICENSE` dosyasındadır. WolfTeam ve Softnyx ilgili hak sahiplerinin markalarıdır; proje bağımsız topluluk çalışmasıdır.
