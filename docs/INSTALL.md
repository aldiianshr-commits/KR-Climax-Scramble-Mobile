# Petunjuk Instalasi — KR Climax Scramble (Android)

Perangkat target: **Xiaomi Pad 6** (Snapdragon 870, Adreno 650, Android 13+/HyperOS).
Perangkat ARM64 lain dengan GPU Adreno 6xx/7xx dan Vulkan 1.1+ juga dapat dipakai.

## Yang Anda perlukan (tidak disertakan di APK)

| File | Dari mana | Taruh di |
|---|---|---|
| Game `.nsp` / `.xci` Kamen Rider Climax Scramble | dump dari kartrid/akun Switch milik Anda | `/storage/emulated/0/KRiderClimax/` |
| `prod.keys` (+ `title.keys` bila ada) | dump dari konsol Switch milik Anda (mis. Lockpick_RCM) | `/storage/emulated/0/KRiderClimax/keys/` |
| Update `.nsp` (opsional) | dump dari konsol Anda | `/storage/emulated/0/KRiderClimax/Updates/` |
| Firmware (opsional, dianjurkan) | dump dari konsol Anda (`.zip` berisi `.nca`, atau file `.nca` lepas) | `/storage/emulated/0/KRiderClimax/firmware/` |

Folder-folder tersebut dibuat otomatis saat aplikasi pertama kali dibuka, jadi Anda bisa
memasang APK dulu lalu menyalin file sesudahnya.

## Langkah pemasangan

1. Unduh `KR-Climax-Scramble-<versi>-arm64.apk` dari halaman **Releases** repositori ini.
2. Buka file APK di tablet → izinkan *Pasang aplikasi tidak dikenal* untuk browser/File Manager →
   **Pasang**. (Di HyperOS, matikan sementara "Pemindaian keamanan" jika pemasangan diblokir.)
3. Buka **KR Climax Scramble**. Di panel **PERSIAPAN** sebelah kanan:
   1. **Izin penyimpanan → Izinkan** → aktifkan *Izinkan akses untuk mengelola semua file* → kembali.
      Folder `KRiderClimax` beserta `saves`, `Updates`, `keys`, `firmware`, `drivers`, `logs` dibuat.
   2. Salin game, `prod.keys`, dan file opsional ke folder di atas (lewat kabel USB atau File Manager).
   3. **Folder game KRiderClimax → Pilih** → layar pemilih folder langsung terbuka di
      `KRiderClimax` → ketuk **Gunakan folder ini** → **Izinkan**.
   4. **Keys → Cek**. Titik berubah hijau bila `prod.keys` valid.
   5. (Opsional) **Firmware → Pasang**.
   6. Driver GPU sudah otomatis memakai **Turnip** pada Adreno. Ganti lewat **Driver GPU → Ganti**.
4. Kartu game di kiri menampilkan judul, versi, dan update yang aktif. Tekan **MAIN**.

Pemuatan pertama lebih lambat dan bisa patah-patah sebentar karena shader sedang dibangun;
shader disimpan sehingga sesi berikutnya jauh lebih mulus.

## Kontrol

* **Kontrol sentuh** tampil otomatis. *Edit Kontrol Sentuh* membuka editor tata letak tanpa game;
  *Tampilan Kontrol Sentuh* mengatur ukuran, opasitas, getar, dan sembunyi otomatis.
* **Gamepad** (Bluetooth/USB: Xbox, DualShock/DualSense, 8BitDo, dll.) dipetakan otomatis ke Player 1
  saat game dimulai. *Kontrol Gamepad* untuk memetakan ulang tombol secara manual.
* Selama bermain, geser dari tepi kiri (atau tombol Back) untuk menu: jeda, simpan/muat,
  pengaturan overlay, keluar.

## Preset performa (Xiaomi Pad 6)

| Preset | Resolusi internal | CPU | Catatan |
|---|---|---|---|
| Performa | 0.75x (540p) + FSR | NCE | FPS paling stabil, panas paling rendah |
| **Seimbang** (bawaan) | 1x (720p) | NCE | Disarankan |
| Kualitas | 1.5x, mode docked | NCE | Lebih tajam, lebih berat |
| Kompatibilitas | 1x | JIT (Dynarmic), akurasi lebih tinggi | Coba bila crash/grafis rusak |

Detail tambahan (filter, anti-aliasing, VSync, dll.) ada di **Grafis Lanjutan**.

## Lokasi save

Save game tersimpan di
`/storage/emulated/0/KRiderClimax/saves/user/save/0000000000000000/<ID pengguna>/<Title ID>/`.
Salin folder `saves` untuk mencadangkan. Saat pertama dipasang, save lama dari folder internal
emulator (jika ada) disalin ke sini.

## Jika ada masalah

* **Game tidak muncul**: pastikan keys valid, file ada langsung di `KRiderClimax/` (bukan di
  `Updates/`), lalu tekan **Pindai Ulang**. Bila ada beberapa judul, tekan **Pilih Game**.
* **Crash / layar hitam**: coba preset **Kompatibilitas**, lalu ganti driver ke *Driver sistem*
  untuk membandingkan. Pastikan firmware terpasang.
* **Kirim laporan**: tekan **Ekspor Log**, lalu bagikan folder di `KRiderClimax/logs/log-<waktu>/`.

## Batasan

* Ini emulasi, bukan port native: hasil akhir bergantung pada kompatibilitas core Eden dengan game
  ini. Status Climax Scramble belum diverifikasi di perangkat fisik dalam proyek ini — build dan
  integrasi sudah diuji, tetapi FPS dan stabilitas di dalam game harus Anda uji sendiri.
* Game, keys, dan firmware **tidak** dan **tidak akan** disertakan; tanpa `prod.keys` milik Anda,
  NSP/XCI tidak bisa didekripsi.
* Multiplayer online Switch (Nintendo Switch Online) tidak tersedia. Mode lokal 2 pemain bisa
  dengan dua gamepad.
* Driver Turnip yang dibundel diambil dari rilis komunitas saat build (lihat catatan Release);
  bila ada regresi, taruh versi lain di `KRiderClimax/drivers/` dan pilih lewat **Driver GPU**.
* APK ditandatangani dengan kunci debug publik dari proyek Eden supaya update bisa dipasang menimpa
  versi lama; bukan untuk distribusi Play Store.
