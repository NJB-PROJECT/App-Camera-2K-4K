# Instruksi Instalasi & Penggunaan

Berikut adalah cara untuk menjalankan aplikasi "SuperCamera" ini di Android Studio.

## Langkah 1: Persiapan Android Studio
1.  Buka Android Studio.
2.  Pilih **New Project** > **Empty Views Activity**.
3.  Set Name: `SuperCamera`.
4.  Set Package Name: `com.example.supercamera`.
5.  Set Language: `Kotlin`.
6.  Klik **Finish**.

## Langkah 2: Menyalin File
Salin isi file-file berikut ke project Anda:

### 1. File Gradle
*   **build.gradle (Project):** Salin kode ke file root.
*   **app/build.gradle:** Salin ke modul `app`.

### 2. Manifest
*   Buka `app/src/main/AndroidManifest.xml` dan tempelkan isinya.

### 3. Layout XML
*   Buka `app/src/main/res/layout/activity_main.xml` dan tempelkan isinya.

### 4. Kode Kotlin
*   Buka `app/src/main/java/com/example/supercamera/`.
*   Buat `AutoFitTextureView.kt` dan tempelkan isinya.
*   Buka `MainActivity.kt` dan tempelkan isinya.

## Fitur Baru (Update V2)

### 1. Reset Auto
Tombol **"Reset Auto"** tersedia untuk mengembalikan pengaturan Fokus dan Eksposur ke mode Otomatis (Continuous AF & Auto AE) setelah Anda menggunakan Slider Manual.

### 2. Touch to Focus
Cukup **sentuh area manapun di layar** (preview kamera) untuk memfokuskan kamera ke titik tersebut.

### 3. RAW Support (DNG)
*   Aktifkan switch **RAW**.
*   Saat mengambil foto, aplikasi akan menyimpan dua file: JPEG (standar) dan DNG (RAW).
*   File DNG memberikan data sensor murni tanpa kompresi, memungkinkan Anda mengeditnya di Lightroom/Snapseed untuk "100% Clarity" dan detail maksimal.
*   *Catatan:* Hanya bekerja jika HP mendukung RAW capture.

### 4. HDR Mode
*   Aktifkan switch **HDR**.
*   Aplikasi akan memaksa mode Scene HDR (High Dynamic Range) jika didukung oleh hardware.
*   Pada Video, ini akan mencoba mengaktifkan profil perekaman kualitas tinggi yang tersedia.

### 5. Galeri
Semua foto/video tersimpan di folder **DCIM/SuperCamera** agar mudah diakses dari Galeri bawaan HP.
