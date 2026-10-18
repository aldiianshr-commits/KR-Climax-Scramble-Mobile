# KR Climax Scramble Mobile

Aplikasi Android (APK) untuk menjalankan **Kamen Rider Climax Scramble** versi Nintendo Switch
(file **NSP / XCI** milik Anda sendiri) di Android, dioptimalkan untuk **Xiaomi Pad 6
(Snapdragon 870 / Adreno 650)** dengan dukungan driver **Turnip**.

> Game ini adalah game Nintendo Switch, bukan game PC. Satu-satunya cara realistis menjalankan
> NSP/XCI di Android adalah lewat emulasi sistem Switch. Proyek ini **membangun ulang core emulator
> open-source [Eden](https://github.com/eden-emulator/mirror) (GPL-3.0)** dari source, lalu
> menambahkan launcher khusus satu game di atasnya. Tidak ada game, keys, atau firmware yang disertakan.

## Unduh

APK terbaru ada di **[Releases](../../releases)** (juga sebagai artefak di tab *Actions*).
Petunjuk pemasangan lengkap: **[docs/INSTALL.md](docs/INSTALL.md)**.

## Fitur

| Fitur | Cara kerja |
|---|---|
| Folder game `/storage/emulated/0/KRiderClimax/` | Dibuat otomatis; dipindai (termasuk subfolder) lewat pemindai game Eden |
| Update/DLC `/KRiderClimax/Updates/` | Didaftarkan sebagai *external content* → dimuat otomatis saat main; opsi pasang permanen ke NAND |
| Save `/KRiderClimax/saves/` | Root save Eden diarahkan ke folder ini (save lama dimigrasi sekali) |
| Keys `/KRiderClimax/keys/prod.keys` | Disalin & divalidasi otomatis setiap dicek |
| Firmware `/KRiderClimax/firmware/` (opsional) | `.zip` atau `.nca` dipasang dengan satu tombol |
| Driver GPU | Turnip (Mesa) dibundel di APK dan dipilih otomatis di GPU Adreno; driver lain bisa ditaruh di `/KRiderClimax/drivers/` |
| Preset performa | Performa / Seimbang / Kualitas / Kompatibilitas, disetel untuk SD870 |
| Kontrol | Kontrol sentuh Eden (edit tata letak tanpa membuka game), mapping gamepad otomatis + manual |
| Grafis, audio, sistem | Halaman pengaturan Eden, dibuka langsung dari launcher |
| Log | Ekspor log + info perangkat ke `/KRiderClimax/logs/` |
| Mode Lanjutan | Antarmuka Eden penuh tetap tersedia |

## Struktur repositori

```
eden.lock                 versi core Eden yang dipin (commit pasti)
overlay/main/             kode launcher (Kotlin) + resource, disalin ke app Android Eden
scripts/apply_overlay.py  menyalin overlay & mengubah manifest/gradle (dengan pengecekan anchor)
scripts/fetch_turnip.py   mengunduh paket Turnip adrenotools untuk dibundel
.github/workflows/        build APK (NDK r28, CMake, Gradle) + publikasi Release
```

## Build sendiri

Build dilakukan di GitHub Actions (`.github/workflows/build-apk.yml`). Secara lokal:

```bash
source eden.lock
git clone --depth 1 --branch "$EDEN_REF" "$EDEN_REPO" eden
python3 scripts/apply_overlay.py eden
python3 scripts/fetch_turnip.py eden/src/android/app/src/main/assets/kr_drivers
cd eden/src/android && ./gradlew assembleMainlineRelease
```

Butuh JDK 17, Android SDK 36, NDK 28.2.13676358, CMake 3.22.1.

## Batasan teknis

Lihat bagian "Batasan" di [docs/INSTALL.md](docs/INSTALL.md).

## Lisensi

GPL-3.0-or-later, sama seperti Eden. Kamen Rider adalah merek dagang pemiliknya; proyek ini
tidak berafiliasi dengan Bandai Namco, Toei, Ishimori Pro, maupun Nintendo.
