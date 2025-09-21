name: Android Build (Signed)

on:
  push:
    branches:
      - master-AIMI-Test
    tags:
      - 'v*.*.*'  # Déclenche sur les tags de version
  pull_request:
    branches:
      - master-AIMI-Test

jobs:
  build-signed:
    runs-on: ubuntu-22.04

    env:
      APP_APK_PATH: app/build/outputs/apk/full/release/app-full-release.apk
      CLIENT_APK_PATH: app/build/outputs/apk/aapsclient/release/app-aapsclient-release.apk
      WEAR_APK_PATH: wear/build/outputs/apk/full/release/wear-full-release.apk

    steps:
      # 1. Récupérer le code
      - name: Check out the code
        uses: actions/checkout@v3

      # 2. Installer Java
      - name: Set up JDK
        uses: actions/setup-java@v3
        with:
          distribution: temurin
          java-version: '21'

      # 3. Recréer le keystore à partir du secret base64
      - name: Decode keystore
        run: |
          echo "$KEYSTORE_BASE64" | base64 --decode > monkeystore.jks
        shell: bash
        env:
          KEYSTORE_BASE64: ${{ secrets.KEYSTORE_BASE64 }}

      # 4. Donner les droits d'exécution à Gradlew
      - name: Grant execute permission for Gradlew
        run: chmod +x gradlew

      # 5. Mettre en cache les dépendances Gradle
      - name: Cache Gradle packages
        uses: actions/cache@v3
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper/
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
          restore-keys: |
            ${{ runner.os }}-gradle-
      # 6. Construire les APKs en mode release (signés)
      - name: Build Release APKs
        run: ./gradlew :app:assembleFullRelease :wear:assembleFullRelease :app:assembleAapsClientRelease
        env:
          KEYSTORE_FILE: ${{ github.workspace }}/monkeystore.jks
          KEYSTORE_PASSWORD: ${{ secrets.KEYSTORE_PASSWORD }}
          KEY_ALIAS: ${{ secrets.KEY_ALIAS }}
          KEY_PASSWORD: ${{ secrets.KEY_PASSWORD }}

      # 7. Vérifier la génération des APKs
      - name: Verify APK Generation
        run: |
          echo "Listing APKs in app module:"
          find app/build/outputs/apk -type f -name "*.apk"
          echo "Listing APKs in wear module:"
          find wear/build/outputs/apk -type f -name "*.apk"
          echo "Listing APKs in client module:"
          find app/build/outputs/apk -type f -name "*.apk"
      # 8. Optionnel : Upload des APKs en tant qu'artefacts
      - name: Upload App APK
        uses: actions/upload-artifact@v4
        with:
          name: app-full-release.apk
          path: ${{ env.APP_APK_PATH }}

      - name: Upload Wear APK
        uses: actions/upload-artifact@v4
        with:
          name: wear-full-release.apk
          path: ${{ env.WEAR_APK_PATH }}

      - name: Upload AAPS Client APK
        uses: actions/upload-artifact@v4
        with:
            name: app-aapsclient-release.apk
            path: ${{ env.CLIENT_APK_PATH }}

      # 9. Optionnel : Créer une Release GitHub et y attacher les APKs
      - name: Create GitHub Release
        if: startsWith(github.ref, 'refs/tags/')
        id: create_release
        uses: softprops/action-gh-release@v1
        with:
          tag_name: ${{ github.ref }}
          name: Release ${{ github.ref }}
          draft: false
          prerelease: false
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

      - name: Upload App APK to Release
        if: startsWith(github.ref, 'refs/tags/')
        uses: softprops/action-gh-release@v1
        with:
          files:
            ${{ env.APP_APK_PATH }}
            ${{ env.CLIENT_APK_PATH }}
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}

      - name: Upload Wear APK to Release
        if: startsWith(github.ref, 'refs/tags/')
        uses: softprops/action-gh-release@v1
        with:
          files: ${{ env.WEAR_APK_PATH }}
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
