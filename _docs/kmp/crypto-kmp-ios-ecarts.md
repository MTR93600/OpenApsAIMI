# Crypto de lien — AES-CMAC et AES-CCM

Base de lecture : `kmp-aimi-migration-study` @ `7c13732990`. Bibliothèque pour X25519, ECDH et ECDSA : `cryptography-kotlin` 0.6.0 (Apache-2.0), déjà utilisée par `CryptoPrimitives` et par le pairing. Aucune pompe n'appelle cette couche. `PUMPDRIVERS` reste `false` dans `IosClientConfig`.

## AES-CMAC et AES-CCM, en commun

Les deux modes sont du Kotlin dans `commonMain`, pas un provider qui les aurait déjà.

- AES-CMAC suit RFC 4493.
- AES-CCM suit NIST SP 800-38C (les paquets RFC 3610 sont la même construction).

La seule primitive de plateforme est le chiffrement d'un bloc AES de 16 octets. ECB sans remplissage, c'est la permutation AES, pas un mode de chaînage :

- iOS : CommonCrypto `CCCrypt`, `kCCAlgorithmAES`, `kCCOptionECBMode`, sans `kCCOptionPKCS7Padding`. L'appel passe par le cinterop de plateforme `platform.CoreCrypto`. Ce dépôt n'ajoute pas de pont Swift.
- JVM et Android : JCE `Cipher.getInstance("AES/ECB/NoPadding")`.

Le tag d'authentification CCM est comparé octet par octet, sans retour au premier octet différent (`constantTimeEquals`). Un tag invalide efface le clair candidat puis lève `AesCcmAuthenticationException` (« authentication tag »). Le clair n'est pas renvoyé, même en partie.

Les longueurs sont refusées avant le chiffrement, avec un `IllegalArgumentException` dont le message nomme le paramètre :

- nonce de 7 à 13 octets ;
- tag M de 4 à 16 octets, pair (32, 48, 64, 80, 96, 112 ou 128 bits) ;
- L = 15 − longueur du nonce, et la longueur du clair doit tenir dans L octets.

Vecteurs dans `commonTest`, donc sur la JVM et dans `:core:objects:iosSimulatorArm64Test` : RFC 4493 exemples 1 à 4, RFC 3610 paquets 1 à 24, NIST SP 800-38C C.1 à C.3, tag retourné, nonce hors bornes, tag impair ou trop court, clair qui ne tient pas dans L, chiffré plus court que le tag.

## Pourquoi BouncyCastle n'est pas le chemin de production

OpenJDK 21 (SunJCE) n'a ni `AES/CCM/NoPadding` ni `AESCMAC`. Une version précédente de cette branche chiffrait ces deux modes avec BouncyCastle. Ce n'est plus le cas : le mode est le code commun, la primitive est le provider JCE par défaut (SunJCE sur une JVM de bureau, le provider Android sur un téléphone).

BouncyCastle 1.81 (`bcprov-jdk18on`, la même coordonnée que `:plugins:libkeks`, licence MIT) n'est plus une dépendance de production de `:core:objects`. Il reste sur le classpath de `jvmTest` et de `androidHostTest`, pour deux raisons de test seulement :

1. Oracle. `Cipher.getInstance("AES/CCM/NoPadding", "BC")` doit reproduire l'exemple publié NIST SP 800-38C C.1. Les octets publiés restent la référence. L'oracle est une deuxième implémentation, pas une comparaison du code avec lui-même.
2. Clé publique X25519. `cryptography-kotlin` dérive cette clé dans `BouncyCastleBridge` : `Class.forName("org.bouncycastle.jce.ECNamedCurveTable")`, puis `X25519PrivateKeyParameters`. SunEC sait calculer le secret partagé, ECDH P-256 et ECDSA P-256, pas cette dérivation. Les classes présentes sur le classpath de test font passer le vecteur RFC 7748. Elles ne sont pas déclarées par le module en production.

## Ce qui reste

X25519 (secret partagé), ECDH P-256 et ECDSA P-256 (vérification SHA-256, signature brute `r || s`) passent par `CryptographyProvider.Default`.

- iOS : provider CryptoKit de la bibliothèque. L'appel Swift est celui qu'elle publie. Pas de pont ajouté ici.
- JVM : SunEC / SunJCE.

Écart restant : sur une JVM de production dont le classpath ne contient pas BouncyCastle, `x25519Public` échoue fermé (la bibliothèque ne dérive pas la clé publique). Le secret partagé X25519, ECDH et ECDSA n'ont pas besoin de ces classes. Aucun driver n'appelle `x25519Public` aujourd'hui.

Pas de copie des tables Abbott (LibreCRKit). Pas de `runCatching` qui avale une erreur sur ce chemin.
