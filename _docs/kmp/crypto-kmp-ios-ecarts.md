# Crypto de lien — ce qu'iOS ne fournit pas

Branche de lecture : `kmp-aimi-migration-study` @ `7c13732990`. Bibliothèque : `cryptography-kotlin` 0.6.0 (Apache-2.0), déjà utilisée par `CryptoPrimitives` et par le pairing. Aucune pompe n'appelle cette couche. `PUMPDRIVERS` reste `false`.

## Ce qui passe des deux côtés

X25519, ECDH P-256 et ECDSA P-256 (vérification SHA-256, signature brute `r || s`) sont dans `commonMain` avec un `expect` / `actual`.

- JVM et Android : `CryptographyProvider.JDK` avec BouncyCastle 1.81 (`bcprov-jdk18on`, la même coordonnée que `:plugins:libkeks`, licence MIT). OpenJDK 21 (SunJCE) n'a ni `AES/CCM/NoPadding` ni `AESCMAC`, et ne dérive pas une clé publique X25519. Le provider n'est pas installé pour tout le processus.
- iOS : provider CryptoKit de la même bibliothèque. L'appel Swift est celui que la bibliothèque publie (`swiftInteropDwcCryptoKitInteropMain`). Ce dépôt n'ajoute pas de pont Swift.

Vecteurs : RFC 7748 §6.1, Wycheproof `ecdh_secp256r1_test.json` tcId 1, Wycheproof `ecdsa_secp256r1_sha256_p1363_test.json` tcId 1 et tcId 11.

## Ce qui s'arrête sur iOS

AES-CCM et AES-CMAC n'ont pas d'actual utile.

| Provider iOS de 0.6.0 | AES-CCM | AES-CMAC | X25519 | ECDH P-256 | ECDSA P-256 |
| --- | --- | --- | --- | --- | --- |
| Apple (CommonCrypto) | non | non | non | non | oui |
| CryptoKit | non | non | oui (X25519 seulement) | oui | oui |
| OpenJDK 21 SunJCE | non | non | accord seulement, pas la clé publique | oui | oui |
| BouncyCastle 1.81 (JVM et Android) | oui | oui | oui | oui | oui |

Les klib `cryptography-provider-apple-iosSimulatorArm64` et `cryptography-provider-cryptokit-iosSimulatorArm64` de 0.6.0 ne contiennent ni `AesCcm` ni `AesCmac`. CommonCrypto n'expose pas CCM ni X25519. CryptoKit n'expose pas CCM ni CMAC.

L'`actual` iOS de ces deux opérations lève une erreur et ne chiffre rien. Il n'y a pas d'implémentation en Kotlin pur, pas de copie des tables Abbott (LibreCRKit), pas de pont Swift ajouté pour contourner CryptoKit. Les vecteurs RFC 4493 (CMAC) et RFC 3610 paquet n°1 / NIST SP 800-38C (CCM) vivent dans `jvmTest` : le job iOS ne les exécute pas, parce qu'ils échoueraient pour absence d'algorithme et non pour une mauvaise réponse.

Le jour où une bibliothèque compatible AGPL fournit CCM et CMAC sur Kotlin/Native sans qu'on réécrive le chiffrement, l'`actual` iOS pourra les brancher. Jusque-là, un driver qui en a besoin ne démarre pas sur iOS.
