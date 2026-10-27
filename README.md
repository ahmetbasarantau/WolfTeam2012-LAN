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

Tüm oyuncular Radmin VPN'i açıp aynı Radmin ağına katılsın. Aynı evdeki oyuncular da Radmin kullanır. Herkes **WolfteamBaslat.bat** dosyasını açar. Sunucu sahibi **1** seçer; arkadaşları **2** seçip açık sunucuyu listeden seçer. Yerel oyuncu adı sorulduğunda mevcut adı seçin veya yeni bir ad oluşturun; parola yoktur.

Başlatıcı Windows'un Radmin kartında bilinen eşleri doğrudan sorgular ve WolfTeam sunucu protokolünü doğrular. Broadcast yanıtları da keşfe yardımcı olur. IP adresi yazmanız veya bağlantı dosyası paylaşmanız gerekmez. Fiziksel Wi-Fi/Ethernet ağı taranmaz.

Sunucu, kurucunun oyunundan bağımsız çalışır: oyun kapanırsa diğer oyuncular devam eder. Kurucu tekrar **1** seçerse açık sunucuya girer; odalar ve bağlı oyuncular korunur. Sunucuyu tamamen kapatmak için aynı başlatıcı menüsünde **3** seçin. Her oyuncu oda kurabilir; maçı o odanın kurucusu başlatır, diğer oyuncular hazır olur. Sunucu bilgisayarının oyuncusunun odada olması gerekmez.

Oyuncu adında Türkçe harfler kullanılabilir; alt çizgi veya tire zorunlu değildir. Eski istemcinin ad alanı en fazla 12 karakterdir.

Başlatıcı, oyunun güncelleme adresini her oturumda seçilen LAN/Radmin sunucusuna yönlendirir ve oyun kapandıktan sonra dosyanın önceki içeriğini geri yükler. Arkadaş bilgisayarında `127.0.0.1` kendi bilgisayarını gösterdiğinden bu yönlendirme güncelleme denetiminin sunucuya ulaşmasını sağlar.

Hata olursa kurulum penceresini kapatmadan son ekrandaki mesajın fotoğrafını oyun kurucusuna gönderin.

## Envanter yöneticisi

`envanter_yoneticisi.bat`, sunucu bilgisayarındaki hesaplara eşya eklemek için isteğe bağlı araçtır. Sunucu kurulumunda gerekli Python/Tk/Pillow bileşenleri oyun klasörünün `.runtime` dizinine hazırlanır; sisteme Python veya paket kurmanız gerekmez. Paneli sunucu bilgisayarında, sunucu kurulumunu tamamladıktan sonra açın.

## Güncelleme ve içerik

Sunucu bilgisayarında yama güncellemeden önce başlatıcıdaki **3** ile sunucuyu kapatın.

Yeni yama sürümü çıktığında GitHub’daki **Code → Download ZIP** ile güncel kaynakları alın ve oyun klasöründeki dosyalarla birleştirin. Oyun dosyalarını tekrar indirmek gerekmez. Yerel hesaplar ve sunucu verileri `.runtime` klasöründe bu bilgisayarda saklanır; bu klasörü paylaşmayın.

## Lisanslar

Proje kaynakları MIT lisanslıdır. Logan sunucu kodunun özgün lisansı `logan/LICENSE` dosyasındadır. WolfTeam ve Softnyx ilgili hak sahiplerinin markalarıdır; proje bağımsız topluluk çalışmasıdır.
