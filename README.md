# Plantões — agenda de plantões médicos para Android

App para organizar plantões em vários hospitais, com alarme **1 dia antes** e **2 horas antes** de cada plantão
(tela piscando sobre o bloqueio, lanterna, som de alarme e vibração), calendário semanal e mensal colorido por
hospital, choques de horário em vermelho e lista de horários livres.

## Instalar
Baixe [`release/Plantoes.apk`](release/Plantoes.apk) no celular Android e toque para instalar
(permita "instalar apps desta fonte"). A partir da versão 1.2 o app procura atualizações sozinho.

## Como o app se atualiza
Ao abrir, o app lê `release/versao.json` deste repositório. Se `versionCode` for maior que o instalado,
mostra as novidades e, com um toque, baixa `release/Plantoes.apk` e abre o instalador.

## Publicar uma versão nova
1. Aumente `versionCode` e `versionName` em `app/build.gradle.kts`.
2. `./gradlew assembleRelease testReleaseUnitTest` (requer JDK 17+ e Android SDK 34 em `local.properties`).
3. Copie `app/build/outputs/apk/release/app-release.apk` para `release/Plantoes.apk`.
4. Atualize `release/versao.json` com o mesmo `versionCode`, `versionName` e as novidades.
5. Faça commit e push em `main`.

A chave de assinatura (`app/plantoes.jks` + `app/keystore.properties`) fica fora do repositório, guardada em local privado do dono. Ela assina todas as versões. Ela precisa ser sempre a mesma: um APK assinado com
outra chave não instala por cima do app e obrigaria a desinstalar (perdendo os plantões salvos no celular).
