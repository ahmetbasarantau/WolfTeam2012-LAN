# WolfTeam 2012 LAN yaması

Bu depo, 2012 istemcisiyle ev ağı veya Radmin VPN üzerinden arkadaşlarla oynamak için gereken LAN yamasını, yerel sunucuyu ve isteğe bağlı envanter yöneticisini içerir. Oyunun kendi dosyaları depoda bulunmaz.

## Oyun dosyalarını indirin

[WolfTeam Türkiye oyun arşivini Archive.org'dan indirin](https://archive.org/details/wolfteamturkiye) ve arşivi bir klasöre çıkarın. Kurulum klasöründe `Wolfteam.exe`, `wolf.xfs` ve `sound.xfs` dosyaları bulunmalı.

## Kurulum

1. GitHub’da **Code → Download ZIP** seçip bu yamayı indirin.
2. Yama ZIP'inin içindeki dosyaları oyun klasörüne çıkarın ve sorulursa var olanlarla birleştirin.
3. Oyun klasöründeki **Kurulum.bat** dosyasına çift tıklayın.
4. Sunucuyu kuracak bilgisayarda **1**; yalnızca arkadaşının sunucusuna bağlanacak bilgisayarda **2** seçin.
5. Kurulum bittiğinde **Y** tuşuna basarsanız oyun doğrudan açılır. Sonradan başlatmak için `WolfteamBaslat.bat` dosyasına çift tıklayın.

Kurulum taşınabilir Python'u indirir ve Direct3D uyumluluk dosyalarını hazırlar. Katılımcı bilgisayara Python veya Java kurmak gerekmez. Sunucuyu kuracak bilgisayarda ilk kurulum için Java JDK 17 veya yenisi gerekir; sunucu, PHP ve MariaDB bileşenlerini kurulum kendisi indirir. İlk kurulum internet bağlantısı gerektirir. Oyunu başlatırken yerel ağ kuralları için Windows yönetici izni istenir.

Ev ağındaki bilgisayarlar aynı ağa bağlansın. Farklı ağlardaki oyuncular aynı Radmin VPN ağına katılsın. Sunucu sahibi `WolfteamBaslat.bat` içinde **1** seçer; arkadaşları **2** seçip listeden sunucuyu seçer. Yerel oyuncu adı sorulduğunda mevcut adı seçin veya yeni bir ad oluşturun; parola yoktur.

Hata olursa kurulum penceresini kapatmadan son ekrandaki mesajın fotoğrafını oyun kurucusuna gönderin.

## Envanter yöneticisi

`envanter_yoneticisi.bat`, sunucu bilgisayarındaki hesaplara eşya eklemek için isteğe bağlı araçtır. Kullanmak için Python 3 ve Tkinter gerekir; paketleri `python -m pip install -r requirements-inventory.txt` komutuyla kurun. Envanter yöneticisi arkadaşlara dağıtılacak kurulum için gerekli değildir.

## Güncelleme ve içerik

Yeni yama sürümü çıktığında GitHub’daki **Code → Download ZIP** ile güncel kaynakları alın ve oyun klasöründeki dosyalarla birleştirin. Oyun dosyalarını tekrar indirmek gerekmez. Yerel hesaplar ve sunucu verileri `.runtime` klasöründe bu bilgisayarda saklanır; bu klasörü paylaşmayın.

## Lisanslar

Proje kaynakları MIT lisanslıdır. Logan sunucu kodunun özgün lisansı `logan/LICENSE` dosyasındadır. WolfTeam ve Softnyx ilgili hak sahiplerinin markalarıdır; proje bağımsız topluluk çalışmasıdır.
