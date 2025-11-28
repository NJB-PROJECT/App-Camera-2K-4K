# Instruksi Instalasi & Penggunaan

Berikut adalah cara untuk menjalankan aplikasi "SuperCamera" ini di Android Studio.

## Langkah 1: Persiapan Android Studio
1.  Buka Android Studio.
2.  Pilih **New Project** > **Empty Views Activity** (atau Empty Activity pada versi lama).
3.  Set Name: `SuperCamera`.
4.  Set Package Name: `com.example.supercamera`.
5.  Set Language: `Kotlin`.
6.  Klik **Finish**.

## Langkah 2: Menyalin File
Setelah project terbuka, salin isi file-file berikut dari hasil generasi saya ke dalam project Anda. Ganti isi file yang ada jika perlu.

### 1. File Gradle
*   **build.gradle (Project: SuperCamera):** Salin kode ke file root `build.gradle` (atau `build.gradle.kts` jika defaultnya kts, sesuaikan sintaksnya). Saya menggunakan Groovy sintaks standar.
*   **app/build.gradle:** Salin ke modul `app`. Pastikan `viewBinding true` ada di blok `android`.

### 2. Manifest
*   Buka `app/src/main/AndroidManifest.xml`.
*   Pastikan permission `CAMERA`, `RECORD_AUDIO`, `WRITE_EXTERNAL_STORAGE` ada.

### 3. Layout XML
*   Buka `app/src/main/res/layout/activity_main.xml`.
*   Tempelkan kode XML layout.

### 4. Kode Kotlin
*   Buka `app/src/main/java/com/example/supercamera/`.
*   Buat file baru `AutoFitTextureView.kt` dan tempelkan kodenya.
*   Buka `MainActivity.kt` dan tempelkan kodenya.

## Langkah 3: Menjalankan Aplikasi
1.  Sambungkan HP Android Anda via USB dan aktifkan USB Debugging.
2.  Klik tombol **Run** (Play hijau) di Android Studio.
3.  Izinkan Permission Kamera & Audio saat diminta.

## Fitur Utama
*   **Max Resolution:** Aplikasi otomatis mencari resolusi tertinggi (misal 4K/8K) yang didukung hardware sensor Anda.
*   **High Bitrate:** Video direkam dengan bitrate tinggi (hingga 100Mbps untuk 4K) agar tajam.
*   **Pro Controls:** Gunakan Slider untuk mengatur Fokus Manual dan ISO.
*   **Stabilization:** OIS/EIS diaktifkan secara otomatis.
*   **Gallery:** Foto dan Video tersimpan di folder DCIM/SuperCamera dan muncul di Galeri HP.

## Catatan
*   Jika preview terlihat miring/gepeng, pastikan rotasi layar HP tidak terkunci, atau sesuaikan logika `configureTransform` jika device Anda memiliki orientasi sensor yang unik.
*   Jika 4K tidak muncul, berarti hardware HP memang membatasi akses tersebut via API pihak ketiga (Samsung/Xiaomi sering membatasi API Camera2 pihak ketiga hanya sampai 1080p/4K30, sementara aplikasi bawaan bisa lebih tinggi). Aplikasi ini berusaha meminta akses maksimal yang diizinkan sistem.
