# WolfTeam 2012 LAN yaması

Bu depo, 2012 istemcisiyle ev ağı veya Radmin VPN üzerinden LAN oyunu kurmaya yarayan yama kaynaklarını, yerel sunucuyu ve envanter yöneticisini içerir.

## Oyun dosyalarını edinme

Bu depoda WolfTeam istemcisi, haritalar, sesler veya XFS arşivleri bulunmaz. Eski WolfTeam Türkiye dosyaları için [Archive.org arşiv sayfasını açın](https://archive.org/details/wolfteamturkiye). Sayfada listelenen dosyalardan birini indirip arşivdeki yönergeye göre çıkarın. Kurulum klasöründe en az `Wolfteam.exe`, `wolf.xfs` ve `sound.xfs` bulunmalı. Arşivdeki istemci sürümünün 2012 Softnyx istemcisiyle uyumlu olduğunu kontrol edin.

## Adım adım kurulum

### 1. Yama kaynaklarını indirin

GitHub’da **Code → Download ZIP** seçin. ZIP’i çıkarın ve içindeki dosyaları oyunun kurulu olduğu klasöre birleştirin. Kaynak kod yeni oyun dosyalarının üzerine yazmaz; repoda oyun istemcisi bulunmaz.

Git kullanıyorsanız güncel yamayı almak için yama klasöründe `git pull` çalıştırın.

### 2. Gerekli araçları kurun

- Windows PowerShell 5 veya PowerShell 7
- Python 3 ve Tkinter
- Java JDK 17 veya daha yenisi
- Direct3D yaması için Windows .NET Framework derleyicileri (`csc.exe` ve `ilasm.exe`)

Envanter yöneticisinin Python paketini kurun:

```powershell
python -m pip install -r requirements-inventory.txt
```

### 3. Yerel sunucuyu hazırlayın

Oyun klasöründe PowerShell açın ve çalıştırın:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\logan\setup.ps1
.\compatibility\build.ps1 -Install
```

Kurulum, PHP ve MariaDB çalışma zamanlarını `.runtime` altına indirir, Java sunucusunu derler ve Direct3D uyumluluk yamasını oyun klasörüne kurar.

### 4. Yerel eşya kataloğunu oluşturun

Envanter yöneticisi ve set içerikleri, oyunun kendi `wolf.xfs` dosyasından yerel katalog üretir. Aynı PowerShell penceresinde:

```powershell
python .\logan\tools\import_asset_catalog.py .\wolf.xfs .\logan\web\wolfteam\asset_catalog.json
python .\logan\tools\import_client_bundles.py .\wolf.xfs .\logan\web\wolfteam\client_bundles.json
```

Kataloglar ve önizleme dosyaları yerelde oluşturulur; GitHub’a gönderilmez.

### 5. Oyunu başlatın

`WolfteamBaslat.bat` dosyasını çalıştırın. Sunucuyu açacak bilgisayarda **1**, arkadaşınızın bilgisayarında **2** seçin. İki bilgisayar aynı Radmin VPN ağına veya aynı ev ağına bağlı olmalı. İlk çalıştırmada Windows Güvenlik Duvarı izni istenebilir.

Yerel hesaplar, envanter ve oyun veritabanı sunucu bilgisayarında `.runtime\logan\data` altında tutulur. Bu klasörü paylaşmayın; yedeklemek isterseniz oyun kapalıyken yedekleyin.

## Envanter yöneticisi

Oyuncu hesabı oluşturulduktan ve yerel sunucu hazırlandıktan sonra `envanter_yoneticisi.bat` dosyasını açın. Listeden hesabı seçip yerel `wolf.xfs` dosyasından üretilen eşya kataloğunda arama yapabilir ve eşyaları hesaba ekleyip kaldırabilirsiniz. Setler içerdikleri eşyalarla birlikte eklenir.

Yönetici yalnızca bu bilgisayardaki yerel sunucuya (`127.0.0.1`) bağlanır. Oyun hesabı/veritabanı ya da envanter yöneticisi dosyası internete gönderilmez. Oyuncuların hesaplarına ve eşyalarına erişimi olan bir yönetim aracıdır; arkadaşlara dağıtacağınız paketlere eklemeyin.

## Güncelleme ve içerik kapsamı

Bu depo yama **kaynak kodudur**, hazır oyun paketi değildir. Son kaynak GitHub’daki `main` dalındadır; Git kullanmayanlar **Code → Download ZIP** ile kaynak anlık görüntüsünü alabilir. Depoda oyun varlıkları, kullanıcı verisi, çalışma günlükleri veya kişisel ağ yapılandırması bulunmaz.

## Lisanslar

Proje kaynakları MIT lisanslıdır. Logan sunucu kodunun özgün MIT lisansı `logan/LICENSE` dosyasındadır. WolfTeam ve Softnyx ilgili hak sahiplerinin markalarıdır; proje bağımsız topluluk çalışmasıdır.
